package com.zutils.bridge.dex;

import com.zutils.bridge.ApiBridge;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 示例 DEX 插件 —— 复制文本到系统剪贴板。
 * 纯 JDK，无 Android 依赖，可直接在服务器编译为 DEX。
 */
public class ClipboardCopyTask {

    private ApiBridge bridge;

    public void setApiBridge(ApiBridge bridge) {
        this.bridge = bridge;
    }

    public String execute(Map<String, String> params) {
        String text = params.get("content");
        if (text == null || text.isEmpty()) {
            return "参数缺失：content";
        }
        List<String> args = Arrays.asList(text);
        return bridge.callApi("clipboard_copy", args);
    }
}
