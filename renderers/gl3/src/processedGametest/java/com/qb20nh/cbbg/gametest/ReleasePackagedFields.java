package com.qb20nh.cbbg.gametest;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
final class ReleasePackagedFields {
  private ReleasePackagedFields() {}

  static @Nullable Object get(String owner, String signature) {
    try {
      Class<?> type = Class.forName(ReleaseMapping.className(owner));
      var field = type.getDeclaredField(ReleaseMapping.memberName(owner, signature));
      field.setAccessible(true);
      return field.get(null);
    } catch (ReflectiveOperationException failure) {
      throw new LinkageError("Cannot inspect packaged GL3 state", failure);
    }
  }
}
