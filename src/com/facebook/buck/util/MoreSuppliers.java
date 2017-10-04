/*
 * Copyright 2016-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.util;

import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import java.lang.ref.WeakReference;
import javax.annotation.Nullable;

public final class MoreSuppliers {
  private MoreSuppliers() {}

  /**
   * Returns a supplier which caches the instance retrieved during the first call to {@code get()}
   * and returns that value on subsequent calls to {@code get()}.
   *
   * <p>Unlike Guava's {@link com.google.common.base.Suppliers#memoize(Supplier)}, this version
   * removes the reference to the underlying Supplier once the value is computed. This frees up
   * memory used in lambda captures, at the cost of causing the supplier to be not Serializable.
   */
  public static <T> java.util.function.Supplier<T> memoize(
      java.util.function.Supplier<T> delegate) {
    return (delegate instanceof MemoizingSupplier) ? delegate : new MemoizingSupplier<T>(delegate);
  }

  private static class MemoizingSupplier<T> implements java.util.function.Supplier<T> {
    // This field doubles as a marker of whether the value has been initialized. Once the value is
    // initialized, this field becomes null.
    @Nullable private volatile java.util.function.Supplier<T> delegate;
    @Nullable private T value;

    public MemoizingSupplier(java.util.function.Supplier<T> delegate) {
      this.delegate = delegate;
    }

    @Override
    public T get() {
      if (delegate != null) {
        synchronized (this) {
          if (delegate != null) {
            T t = Preconditions.checkNotNull(delegate.get());
            value = t;
            delegate = null;
            return t;
          }
        }
      }
      return Preconditions.checkNotNull(value);
    }
  }

  public static <T> Supplier<T> weakMemoize(Supplier<T> delegate) {
    return (delegate instanceof WeakMemoizingSupplier)
        ? delegate
        : new WeakMemoizingSupplier<>(delegate);
  }

  private static class WeakMemoizingSupplier<T> implements Supplier<T> {
    private final Supplier<T> delegate;
    private WeakReference<T> valueRef;

    public WeakMemoizingSupplier(Supplier<T> delegate) {
      this.delegate = delegate;
      this.valueRef = new WeakReference<>(null);
    }

    @Override
    public T get() {
      @Nullable T value = valueRef.get();
      if (value == null) {
        synchronized (this) {
          // Check again in case someone else has populated the cache.
          value = valueRef.get();
          if (value == null) {
            value = delegate.get();
            valueRef = new WeakReference<>(value);
          }
        }
      }
      return value;
    }
  }
}
