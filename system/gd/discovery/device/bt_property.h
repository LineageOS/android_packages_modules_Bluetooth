/*
 * Copyright 2024 The Android Open Source Project
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

#include <memory>
#include <string>
#include <vector>

#include "hardware/bluetooth.h"
#include "include/hardware/bluetooth.h"
#include "stack/include/bt_name.h"

using namespace bluetooth;

using BtPropertyName = std::vector<uint8_t>;

class BtProperty;

std::string bt_property_text(const bt_property_t& property);
std::string bt_property_type_text(const bt_property_type_t& type);

class BtProperty {
 public:
  // Return size in bytes of property data value
  virtual size_t Size() const = 0;
  // Returns raw pointer to the data value
  virtual const void* Val() const = 0;

  bt_property_type_t Type() const {
    return type_;
  }

  std::string ToString() const {
    return bt_property_type_text(type_);
  }

  // Serialize the property object into the provided vector
  static void Serialize(
      const std::vector<std::shared_ptr<BtProperty>>& bt_properties,
      std::vector<bt_property_t>& properties);

  // Serialize the property object into the provided memory
  static void Serialize(
      const std::vector<std::shared_ptr<BtProperty>>& bt_properties,
      bt_property_t* property,
      size_t max_len);

 protected:
  BtProperty(bt_property_type_t type) : type_(type) {}
  virtual ~BtProperty() = default;

 private:
  bt_property_type_t type_;
};

template <typename T>
class BtPropertySimple : public BtProperty {
 public:
  virtual size_t Size() const override {
    return sizeof(T);
  }

  const void* Val() const override {
    return (const void*)val_.get();
  }

  friend class BtPropertyFactory;

  static std::shared_ptr<BtPropertySimple<T>> Factory(bt_property_type_t type, const T& val);

  BtPropertySimple<T>(bt_property_type_t type, T val)
      : BtProperty(type), val_(std::make_shared<T>(val)) {}

 private:
  std::shared_ptr<T> val_;
};

class BtPropertyBdAddr : public BtPropertySimple<RawAddress> {
 public:
  BtPropertyBdAddr(const RawAddress& bd_addr)
      : BtPropertySimple<RawAddress>(BT_PROPERTY_BDADDR, bd_addr) {}
};

template <typename T>
class BtPropertyVector : public BtProperty {
 public:
  virtual size_t Size() const override {
    return sizeof(T) * val_->size();
  }
  const void* Val() const override {
    return (const void*)&(*val_)[0];
  }

  //  friend class BtPropertyFactory;

  // General vector usage
  static std::shared_ptr<BtPropertyVector<T>> Factory(
      bt_property_type_t type, const std::vector<T>& vector);

  // Use for bluetooth name arrays
  static std::shared_ptr<BtPropertyVector<T>> Factory(bt_property_type_t type, BD_NAME name);

  // Use for C raw addresses
  static std::shared_ptr<BtPropertyVector<T>> Factory(
      bt_property_type_t type, const T* name, size_t size);

  // Create a vector property from another vector
  BtPropertyVector<T>(bt_property_type_t type, const std::vector<T>& val)
      : BtProperty(type), val_(std::make_shared<std::vector<T>>(val)) {}

  // Create a vector property from a raw pointer and size
  BtPropertyVector<T>(bt_property_type_t type, const T* val, size_t size)
      : BtProperty(type), val_(std::make_shared<std::vector<T>>(val, val + size)) {}

 private:
  std::shared_ptr<std::vector<T>> val_;
};

template <typename T>
std::shared_ptr<BtPropertyVector<T>> BtPropertyVector<T>::Factory(
    bt_property_type_t type, const std::vector<T>& vector) {
  return std::make_shared<BtPropertyVector<T>>(BtPropertyVector<T>(type, vector));
}

template <typename T>
std::shared_ptr<BtPropertyVector<T>> BtPropertyVector<T>::Factory(
    bt_property_type_t type, BD_NAME name) {
  return std::make_shared<BtPropertyVector<T>>(BtPropertyVector<T>(type, name, kBdNameLength));
}

template <typename T>
std::shared_ptr<BtPropertyVector<T>> BtPropertyVector<T>::Factory(
    bt_property_type_t type, const T* val, size_t size) {
  return std::make_shared<BtPropertyVector<T>>(BtPropertyVector<T>(type, val, size));
}

template <typename T>
std::shared_ptr<BtPropertySimple<T>> BtPropertySimple<T>::Factory(
    bt_property_type_t type, const T& val) {
  return std::make_shared<BtPropertySimple<T>>(BtPropertySimple<T>(type, val));
}

class BtPropertyFactory {
 public:
  // General vector usage
  template <typename T>
  static std::shared_ptr<BtProperty> Build(bt_property_type_t type, const std::vector<T>& vector);

  // Use for C raw addresses
  template <typename T>
  static std::shared_ptr<BtProperty> Build(bt_property_type_t type, const T* ptr, size_t size);

  // Use for bluetooth name arrays
  static std::shared_ptr<BtProperty> Build(bt_property_type_t type, BD_NAME name);

  // Use for simple type properties
  template <typename T>
  static std::shared_ptr<BtProperty> Build(bt_property_type_t type, const T& val);

  // Bt property generator wrapping in shared pointer
  template <typename T, typename U>
  static std::shared_ptr<T> Build(const U& val);
};

template <typename T, typename U>
std::shared_ptr<T> BtPropertyFactory::Build(const U& val) {
  return std::make_shared<T>(T(val));
}

template <typename T>
std::shared_ptr<BtProperty> BtPropertyFactory::Build(
    bt_property_type_t type, const std::vector<T>& vector) {
  return BtPropertyVector<T>::Factory(type, vector);
}

template <typename T>
std::shared_ptr<BtProperty> BtPropertyFactory::Build(
    bt_property_type_t type, const T* ptr, size_t size) {
  return BtPropertyVector<T>::Factory(type, ptr, size);
}

template <typename T>
std::shared_ptr<BtProperty> BtPropertyFactory::Build(bt_property_type_t type, const T& val) {
  return BtPropertySimple<T>::Factory(type, val);
}
