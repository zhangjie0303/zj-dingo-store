/*
 * Copyright 2021 DataCanvas
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.dingodb.sdk.service.connector;

import io.dingodb.error.ErrorOuterClass;
import io.dingodb.sdk.common.DingoClientException.InvalidRouteTableException;
import io.dingodb.sdk.common.DingoClientException.RequestErrorException;
import io.dingodb.sdk.common.DingoClientException.RetryException;
import io.dingodb.sdk.common.Location;
import io.dingodb.sdk.common.utils.ErrorCodeUtils;
import io.dingodb.sdk.common.utils.NoBreakFunctions;
import io.dingodb.sdk.common.utils.Optional;
import io.grpc.Channel;
import io.grpc.ManagedChannel;
import io.grpc.stub.AbstractBlockingStub;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.dingodb.sdk.common.utils.ErrorCodeUtils.defaultCodeChecker;
import static io.dingodb.sdk.common.utils.NoBreakFunctions.wrap;

@Slf4j
public abstract class ServiceConnector<S extends AbstractBlockingStub<S>> {

    public static final int RETRY_TIMES = 30;
    private static Map<Class, ResponseBuilder> responseBuilders = new ConcurrentHashMap<>();
    private static ThreadLocal<Map<String, Integer>> ERR_MSGS = ThreadLocal.withInitial(HashMap::new);

    @Getter
    @AllArgsConstructor
    public static class Response<R> {
        private final ErrorOuterClass.Error error;
        private final R response;
    }

    @AllArgsConstructor
    private static class ResponseBuilder<R> {
        private final Method errorGetter;

        public Response<R> build(R response) {
            try {
                return new Response<>((ErrorOuterClass.Error) errorGetter.invoke(response), response);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }


    private final AtomicBoolean refresh = new AtomicBoolean();

    protected final AtomicReference<S> stubRef = new AtomicReference<>();
    protected Set<Location> locations = new CopyOnWriteArraySet<>();

    public ServiceConnector(String locations) {
        this(Optional.ofNullable(locations)
            .map(__ -> __.split(","))
            .map(Arrays::stream)
            .map(ss -> ss
                .map(s -> s.split(":"))
                .map(__ -> new Location(__[0], Integer.parseInt(__[1])))
                .collect(Collectors.toSet()))
            .orElseGet(Collections::emptySet));
    }

    public ServiceConnector(Set<Location> locations) {
        this.locations.addAll(locations);
    }

    public S getStub() {
        return stubRef.get();
    }

    private <R> Response<R> toResponse(Object res) {
        return responseBuilders.computeIfAbsent(res.getClass(), NoBreakFunctions.<Class, ResponseBuilder>wrap(
            cls -> new ResponseBuilder<>(cls.getDeclaredMethod("getError")))
        ).build(res);
    }

    private <R> R cleanResponse(Response<R> response) {
        return Optional.mapOrNull(response, Response::getResponse);
    }

    public <R> R exec(Function<S, R> function) {
        return cleanResponse(this.exec(function, RETRY_TIMES, defaultCodeChecker, this::toResponse));
    }

    public <R> R exec(Function<S, R> function, int retryTimes) {
        return cleanResponse(this.exec(function, retryTimes, defaultCodeChecker, this::toResponse));
    }

    public <R> R exec(Function<S, R> function, Function<Integer, ErrorCodeUtils.InternalCode> errChecker) {
        return cleanResponse(exec(function, RETRY_TIMES, errChecker, this::toResponse));
    }

    public <R> R exec(
        Function<S, R> function, int retryTimes, Function<Integer, ErrorCodeUtils.InternalCode> errChecker
    ) {
        return cleanResponse(exec(function, retryTimes, errChecker, this::toResponse));
    }

    public <R> Response<R> exec(
            Function<S, R> function,
            int retryTimes,
            Function<Integer, ErrorCodeUtils.InternalCode> errChecker,
            Function<R, Response<R>> toResponse
    ) {
        S stub = null;
        boolean connected = false;
        Map<String, Integer> errMsgs = ERR_MSGS.get();
        errMsgs.clear();

        while (retryTimes-- > 0) {
            try {
                if ((stub = getStub()) == null) {
                    if (log.isDebugEnabled()) {
                        log.warn("Get connection stub failed, will retry...");
                    }
                    LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(1));
                    refresh(stub);
                    continue;
                }

                connected = true;
                Response<R> response = toResponse.apply(function.apply(stub));
                ErrorOuterClass.Error error = response.getError();
                int errCode = error.getErrcodeValue();
                if (errCode != 0) {
                    String authority = Optional.mapOrGet(stub.getChannel(), Channel::authority, () -> "");
                    errMsgs.compute(authority + ">>" + error.getErrmsg(), (k, v) -> v == null ? 1 : v + 1);
                    switch (errChecker.apply(errCode)) {
                        case RETRY:
                            if (log.isDebugEnabled()) {
                                log.warn(
                                    "Exec {} failed, store: [{}], code: [{}], message: {}, will retry...",
                                    function.getClass(), authority, error.getErrcode(), error.getErrmsg()
                                );
                            }
                            LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(1));
                            refresh(stub);
                            continue;
                        case FAILED:
                            log.error(
                                    "Exec {} error, store: [{}], code: [{}], message: {}.",
                                    function.getClass(), authority, response.error.getErrcode(), response.error.getErrmsg()
                            );
                            throw new RequestErrorException(errCode, error.getErrmsg());
                        case REFRESH:
                            if (log.isDebugEnabled()) {
                                log.warn(
                                    "Exec {} failed, store: [{}], code: [{}], message: {}, will refresh...",
                                    function.getClass(), authority, error.getErrcode(), error.getErrmsg()
                                );
                            }
                            LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(1));
                            refresh(stub);
                            throw new InvalidRouteTableException(response.error.getErrmsg());
                        case IGNORE:
                            if (log.isDebugEnabled()) {
                                log.warn(
                                    "Exec {} failed, store: [{}], code: [{}], message: {}, ignore it.",
                                    function.getClass(), authority, response.error.getErrcode(), response.error.getErrmsg()
                                );
                            }
                            return null;
                        default:
                            throw new IllegalStateException("Unexpected value: " + errChecker.apply(errCode));
                    }
                }
                return response;
            } catch (Exception e) {
                if (e instanceof RequestErrorException || e instanceof InvalidRouteTableException) {
                    throw e;
                }
                if (log.isDebugEnabled()) {
                    log.warn("Exec {} failed: {}.", function.getClass(), e.getMessage());
                }
                errMsgs.compute(e.getMessage(), (k, v) -> v == null ? 1 : v + 1);
                LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(1));
                refresh(stub);
            }
        }

        // if connected is false, means can not get leader connection
        if (connected) {
            StringBuilder errMsgBuilder = new StringBuilder();
            errMsgBuilder.append("function: ").append(function.getClass()).append("==>>");
            errMsgs.forEach((k, v) -> errMsgBuilder
                .append('[').append(v).append("] times [").append(k).append(']').append(", ")
            );
            throw new RetryException(
                "Exec attempts exhausted, failed to exec operation, " + errMsgBuilder
            );
        } else {
            throw new RetryException("Transform leader attempts exhausted, cannot get leader connection.");
        }
    }

    public void refresh(S stub) {
        if (!refresh.compareAndSet(false, true)) {
            return;
        }
        try {
            if (!stubRef.compareAndSet(stub, null)) {
                return;
            }

            if (locations == null || locations.isEmpty()) {
                Optional.ofNullable(this.transformToLeaderChannel(null))
                    .map(this::newStub)
                    .ifPresent(stubRef::set);
                return;
            }

            for (Location location : locations) {
                if (Optional.of(location)
                    .map(this::newChannel)
                    .map(wrap(this::transformToLeaderChannel))
                    .map(this::newStub)
                    .ifPresent(stubRef::set)
                    .isPresent()
                ) {
                    return;
                }
            }
        } finally {
            refresh.set(false);
        }
    }
    
    protected ManagedChannel newChannel(Location location) {
        try {
            return ChannelManager.getChannel(location);
        } catch (Exception e) {
            log.warn("Connect {} error", location, e);
        }
        return null;
    } 

    protected ManagedChannel newChannel(String host, int port) {
        return newChannel(new Location(host, port));
    }

    protected abstract ManagedChannel transformToLeaderChannel(ManagedChannel channel);

    protected abstract S newStub(ManagedChannel channel);

    public Set<Location> getLocations() {
        return locations;
    }

}
