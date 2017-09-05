/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.rules.modern.impl;

import com.facebook.buck.log.Logger;
import com.facebook.buck.rules.modern.Buildable;
import com.facebook.buck.rules.modern.ClassInfo;
import com.google.common.base.Preconditions;
import java.lang.reflect.Modifier;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Creates and caches the default ClassInfo implementations. */
public class DefaultClassInfoFactory {
  private static final Logger LOG = Logger.get(DefaultClassInfo.class);

  public static <T extends Buildable> ClassInfo<T> forBuildable(T buildable) {
    @SuppressWarnings("unchecked")
    ClassInfo<T> classInfo = (ClassInfo<T>) getClassInfo(buildable.getClass());
    return classInfo;
  }

  private static final ConcurrentHashMap<Class<?>, ClassInfo<? extends Buildable>> classesInfo =
      new ConcurrentHashMap<>();

  private static ClassInfo<?> getClassInfo(Class<?> clazz) {
    ClassInfo<?> info = classesInfo.get(clazz);
    if (info != null) {
      return info;
    }
    try {
      Preconditions.checkArgument(
          Buildable.class.isAssignableFrom(clazz),
          "%s is not assignable to Buildable.",
          clazz.getName());
      Class<?> superClazz = clazz.getSuperclass();
      if (!superClazz.equals(Object.class)) {
        // This ensures that classesInfo holds an entry for the super class and computeClassInfo
        // can get it without having to modify the map.
        LOG.verbose(
            String.format(
                "Getting superclass %s info for %s.", clazz.getName(), superClazz.getName()));
        getClassInfo(superClazz);
      }
      info = classesInfo.computeIfAbsent(clazz, DefaultClassInfoFactory::computeClassInfo);
      return info;
    } catch (Exception e) {
      throw new IllegalStateException(
          String.format("Failed getting class info for %s: %s", clazz.getName(), e.getMessage()),
          e);
    }
  }

  private static <T extends Buildable> ClassInfo<T> computeClassInfo(Class<?> clazz) {
    // Lambdas, anonymous and local classes can easily access variables that we can't find via
    // reflection.
    Preconditions.checkArgument(
        !clazz.isAnonymousClass(), "Buildables cannot be anonymous classes.");
    Preconditions.checkArgument(!clazz.isLocalClass(), "Buildables cannot be local classes.");
    Preconditions.checkArgument(!clazz.isSynthetic(), "Buildables cannot be synthetic.");
    // We don't want to have to deal with inner non-static classes (and verifying usage of state
    // from the outer class).
    Preconditions.checkArgument(
        !clazz.isMemberClass() || Modifier.isStatic(clazz.getModifiers()),
        "Buildables cannot be inner non-static classes.");

    Optional<ClassInfo<? super T>> superInfo = Optional.empty();
    Class<?> superClazz = clazz.getSuperclass();
    if (!superClazz.equals(Object.class)) {
      // It's guaranteed that the superclass's info is already computed.
      @SuppressWarnings("unchecked")
      ClassInfo<? super T> superClazzInfo = (ClassInfo<? super T>) classesInfo.get(superClazz);
      superInfo = Optional.of(superClazzInfo);
    }
    return new DefaultClassInfo<>(clazz, superInfo);
  }
}
