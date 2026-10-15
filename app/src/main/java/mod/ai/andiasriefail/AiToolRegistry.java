package mod.ai.andiasriefail;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The list of tools the AI can call. Add a new tool by adding it to one of the Tools* classes.
 */
public final class AiToolRegistry {

    private final Map<String, AiTool> tools = new LinkedHashMap<>();

    public AiToolRegistry() {
        List<AiTool> all = new ArrayList<>();
        all.addAll(ToolsFiles.all());
        all.addAll(ToolsProject.all());
        all.addAll(ToolsManifest.all());
        all.addAll(ToolsLibrary.all());
        all.addAll(ToolsBuild.all());
        all.addAll(ToolsAttachments.all());
        all.addAll(ToolsEdit.all());
        all.addAll(ToolsNav.all());
        all.addAll(ToolsConfig.all());
        for (AiTool tool : all) tools.put(tool.name(), tool);
    }

    public AiTool get(String name) {
        return tools.get(name);
    }

    public int size() {
        return tools.size();
    }

    public JSONArray toApiJson() throws Exception {
        JSONArray array = new JSONArray();
        for (AiTool tool : tools.values()) array.put(tool.toApiJson());
        return array;
    }
}
