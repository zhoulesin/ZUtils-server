package com.zutils.bridge.dex;

import com.zutils.bridge.ApiBridge;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 示例 DEX 插件 —— 写入文本文件。
 * 纯 JDK，无 Android 依赖，可直接在服务器编译为 DEX。
 */
public class WriteFileTask {

    private ApiBridge bridge;

    public void setApiBridge(ApiBridge bridge) {
        this.bridge = bridge;
    }

    public String execute(Map<String, String> params) {
        String folderName = params.get("folderName");
        String fileName = params.get("fileName");
        String content = params.get("content");
        if (folderName == null || fileName == null || content == null) {
            return "参数缺失：folderName/fileName/content";
        }
        List<String> args = Arrays.asList(folderName, fileName, content);
        return bridge.callApi("file_write", args);
    }
}
