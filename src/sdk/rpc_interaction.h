// Copyright (c) 2023 dingodb.com, Inc. All Rights Reserved
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#ifndef DINGODB_SDK_RPC_INTERACTION_H_
#define DINGODB_SDK_RPC_INTERACTION_H_

#include <cstdint>
#include <map>
#include <memory>
#include <mutex>

#include "brpc/channel.h"
#include "butil/endpoint.h"
#include "sdk/status.h"
#include "rpc.h"

namespace dingodb {
namespace sdk {

class RpcInteraction {
 public:
  RpcInteraction(const RpcInteraction &) = delete;
  const RpcInteraction &operator=(const RpcInteraction &) = delete;

  RpcInteraction(brpc::ChannelOptions options) : options_(std::move(options)) {}

  virtual ~RpcInteraction() = default;

  // return value: Status::Uninitialized or Status::OK
  virtual Status SendRpc(Rpc& rpc, google::protobuf::Closure* done = nullptr);

 protected:
  // return value: Status::Uninitialized or Status::OK
  virtual Status InitChannel(const butil::EndPoint& server_addr_and_port, std::shared_ptr<brpc::Channel> &channel);

 private:
  brpc::ChannelOptions options_;

  std::mutex lock_;
  std::map<butil::EndPoint, std::shared_ptr<brpc::Channel>> channel_map_;
};

}  // namespace sdk
}  // namespace dingodb
#endif  // DINGODB_SDK_RPC_INTERACTION_H_