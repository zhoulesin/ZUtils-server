package com.zutils.bridge;

import java.util.List;

/**
 * 通用跨端调用桥。
 * 纯 JDK，无 Android 依赖，云端和 DEX 都可直接编译。
 *
 * DEX 插件通过此桥调用宿主能力，宿主在 AppApiBridge 中反射实现。
 * apiTag 为自定义业务标识，不用原生类名，更安全。
 */
public interface ApiBridge {
    String callApi(String apiTag, List<String> params);
}
