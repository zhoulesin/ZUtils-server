package com.zutils.bridge.dex;

import com.zutils.bridge.ApiBridge;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 示例 DEX 插件 —— 创建文件夹。
 * 纯 JDK，无 Android 依赖，可直接在服务器编译为 DEX。
 */
public class CreateFolderTask {

    private ApiBridge bridge;

    public void setApiBridge(ApiBridge bridge) {
        this.bridge = bridge;
    }

    public String execute(Map<String, String> params) {
        String folderName = params.get("folderName");
        if (folderName == null || folderName.isEmpty()) {
            return "参数缺失：folderName";
        }
        List<String> args = Arrays.asList(folderName);
        return bridge.callApi("folder_create", args);
    }
}
