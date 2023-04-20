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

#include "long_schema.h"

namespace dingodb {

int DingoSchema<optional<int64_t>>::GetDataLength() {
  return 8;
}
int DingoSchema<optional<int64_t>>::GetWithNullTagLength() {
  return 9;
}
void DingoSchema<optional<int64_t>>::InternalEncodeNull(Buf* buf) {
  buf->Write(0);
  buf->Write(0);
  buf->Write(0);
  buf->Write(0);
  buf->Write(0);
  buf->Write(0);
  buf->Write(0);
  buf->Write(0);
}
void DingoSchema<optional<int64_t>>::InternalEncodeKey(Buf* buf, int64_t data) {
  uint64_t* l = (uint64_t*)&data;
  buf->Write(*l >> 56 ^ 0x80);
  buf->Write(*l >> 48);
  buf->Write(*l >> 40);
  buf->Write(*l >> 32);
  buf->Write(*l >> 24);
  buf->Write(*l >> 16);
  buf->Write(*l >> 8);
  buf->Write(*l);
}
void DingoSchema<optional<int64_t>>::InternalEncodeValue(Buf* buf, int64_t data) {
  uint64_t* l = (uint64_t*)&data;
  buf->Write(*l >> 56);
  buf->Write(*l >> 48);
  buf->Write(*l >> 40);
  buf->Write(*l >> 32);
  buf->Write(*l >> 24);
  buf->Write(*l >> 16);
  buf->Write(*l >> 8);
  buf->Write(*l);
}

BaseSchema::Type DingoSchema<optional<int64_t>>::GetType() {
  return kLong;
}
void DingoSchema<optional<int64_t>>::SetIndex(int index) {
  this->index_ = index;
}
int DingoSchema<optional<int64_t>>::GetIndex() {
  return this->index_;
}
void DingoSchema<optional<int64_t>>::SetIsKey(bool key) {
  this->key_ = key;
}
bool DingoSchema<optional<int64_t>>::IsKey() {
  return this->key_;
}
int DingoSchema<optional<int64_t>>::GetLength() {
  if (this->allow_null_ ) {
    return GetWithNullTagLength();
  }
  return GetDataLength();
}
void DingoSchema<optional<int64_t>>::SetAllowNull(bool allow_null) {
  this->allow_null_ = allow_null;
}
bool DingoSchema<optional<int64_t>>::AllowNull() {
  return this->allow_null_;
}
void DingoSchema<optional<int64_t>>::EncodeKey(Buf* buf, optional<int64_t> data) {
  if (this->allow_null_ ) {
    buf->EnsureRemainder(GetWithNullTagLength());
    if (data.has_value()) {
      buf->Write(k_not_null);
      InternalEncodeKey(buf, data.value());
    } else {
      buf->Write(k_null);
      InternalEncodeNull(buf);
    }
  } else {
    if (data.has_value()) {
      buf->EnsureRemainder(GetDataLength());
      InternalEncodeKey(buf, data.value());
    } else {
      // WRONG EMPTY DATA
    }
  }
}
optional<int64_t> DingoSchema<optional<int64_t>>::DecodeKey(Buf* buf) {
  if (this->allow_null_ ) {
    if (buf->Read() == this->k_null) {
      buf->Skip(GetDataLength());
      return nullopt;
    }
  }
  uint64_t l = buf->Read() & 0xFF ^ 0x80;
  for (int i = 0; i < 7; i++) {
    l <<= 8;
    l |= buf->Read() & 0xFF;
  }
  return l;
}
void DingoSchema<optional<int64_t>>::SkipKey(Buf* buf) {
  buf->Skip(GetLength());
}
void DingoSchema<optional<int64_t>>::EncodeValue(Buf* buf, optional<int64_t> data) {
  if (this->allow_null_ ) {
    buf->EnsureRemainder(GetWithNullTagLength());
    if (data.has_value()) {
      buf->Write(k_not_null);
      InternalEncodeValue(buf, data.value());
    } else {
      buf->Write(k_null);
      InternalEncodeNull(buf);
    }
  } else {
    if (data.has_value()) {
      buf->EnsureRemainder(GetDataLength());
      InternalEncodeValue(buf, data.value());
    } else {
      // WRONG EMPTY DATA
    }
  }
}
optional<int64_t> DingoSchema<optional<int64_t>>::DecodeValue(Buf* buf) {
  if (this->allow_null_ ) {
    if (buf->Read() == this->k_null) {
      buf->Skip(GetDataLength());
      return nullopt;
    }
  }
  uint64_t l = buf->Read() & 0xFF;
  for (int i = 0; i < 7; i++) {
    l <<= 8;
    l |= buf->Read() & 0xFF;
  }
  return l;
}
void DingoSchema<optional<int64_t>>::SkipValue(Buf* buf) {
  buf->Skip(GetLength());
}

}  // namespace dingodb