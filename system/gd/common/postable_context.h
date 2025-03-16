/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#pragma once

#include "common/bind.h"
#include "common/contextual_callback.h"
#include "common/i_postable_context.h"

namespace bluetooth::common {

class PostableContext : public IPostableContext {
public:
  virtual ~PostableContext() = default;

  template <typename Functor, typename... Args>
  auto BindOnce(Functor&& functor, Args&&... args) {
    return common::ContextualOnceCallback(
            common::BindOnce(std::forward<Functor>(functor), std::forward<Args>(args)...), this);
  }

  template <typename Functor, typename T, typename... Args>
  auto BindOnceOn(T* obj, Functor&& functor, Args&&... args) {
    return common::ContextualOnceCallback(
            common::BindOnce(std::forward<Functor>(functor), common::Unretained(obj),
                             std::forward<Args>(args)...),
            this);
  }

  template <typename Functor, typename... Args>
  auto Bind(Functor&& functor, Args&&... args) {
    return common::ContextualCallback(
            common::Bind(std::forward<Functor>(functor), std::forward<Args>(args)...), this);
  }

  template <typename Functor, typename T, typename... Args>
  auto BindOn(T* obj, Functor&& functor, Args&&... args) {
    return common::ContextualCallback(
            common::Bind(std::forward<Functor>(functor), common::Unretained(obj),
                         std::forward<Args>(args)...),
            this);
  }
};

}  // namespace bluetooth::common
