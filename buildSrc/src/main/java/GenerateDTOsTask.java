package io.bokun.octo.gradle;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import org.yaml.snakeyaml.Yaml;

public class GenerateDTOsTask {
    private final String filename;
    private final String buildDir;
    private final Map<String,ArrayList<String>> enums = new HashMap<>();

    public GenerateDTOsTask(String filename, String buildDir) {
        this.filename = filename;
        this.buildDir = buildDir;
    }

    public static void generateDTOs(String filename, String buildDir) {
        var instance = new GenerateDTOsTask(filename, buildDir);
        instance.generate();
    }

    private void registerEnum(String name, ArrayList<String> options) {
        if (name == null || options == null) {
            return;
        }

        enums.put(name, options);
    }

    private void createEnums() {
        for(var entry : enums.entrySet()) {
            createJavaEnum(entry.getKey(), entry.getValue());
        }
    }

    private String capitalize(String input) {
        return input.substring(0, 1).toUpperCase() + input.substring(1);
    }

    private String makeObjectName(String input, Boolean typeAsSingular) {
        input = capitalize(input);

        if (!typeAsSingular) {
            return input;
        }

        if (input.endsWith("s")) {
            return input.substring(0, input.length() - 1);
        }

        return input;
    }

    private String getType(
            String type,
            Map<String, Object> prop,
            String propName,
            String schemaName,
            Boolean typeAsSingular
    ) {
        if (type.equals("array")) {
            var items = (Map<String, Object>) prop.get("items");
            return "ArrayList<" + processAndGetType(items, propName, schemaName, true) + ">";
        }

        if (type.equals("object") && propName.equals("restrictions")) {
            propName = schemaName + capitalize(propName);
        }

        return switch (type) {
            case "string" -> "String";
            case "boolean" -> "Boolean";
            case "integer" -> "Integer";
            case "object" -> makeObjectName(propName, typeAsSingular);
            default ->
                    throw new RuntimeException("getType: " + propName + ": Unknown type: " + type + " " + prop.toString());
        };
    }

    private String processAndGetType(
            Map<String, Object> item,
            String itemName,
            String schemaName,
            Boolean typeAsSingular
    ) {
        if (item.containsKey("oneOf")) {
            for(var child : (ArrayList<Map<String, Object>>) item.get("oneOf")) {

                if (! child.containsKey("type")) {
                    continue;
                }

                if (child.get("type").equals("null")) {
                    continue;
                }

                return processAndGetType(child, itemName, schemaName, typeAsSingular);
            }
        }

        var type = item.get("type");

        if (type instanceof String) {
            return processAndGetType((String) type, item, itemName, schemaName, typeAsSingular);
        } else if (type instanceof ArrayList<?>) {
            return processAndGetType((ArrayList<?>) type, item, itemName, schemaName, typeAsSingular);
        }

        throw new RuntimeException("processAndGetType: " + itemName + ": Unknown type: " + (type != null ? type.getClass() : "null") + " " + item);
    }

    private String processAndGetType(
            ArrayList<?> type,
            Map<String, Object> item,
            String itemName,
            String schemaName,
            Boolean typeAsSingular
    ) {
        for (var t : type) {
            if (t.equals("null"))
                continue;
            if (!(t instanceof String))
                continue;
            return processAndGetType((String) t, item, itemName, schemaName, typeAsSingular);
        }

        return "Null";
    }

    private String processAndGetType(
            String type,
            Map<String, Object> item,
            String itemName,
            String schemaName,
            Boolean typeAsSingular
    ) {
        if (type.equals("string")) {
            registerEnum((String) item.get("title"), (ArrayList<String>) item.get("enum"));
        }

        if (type.equals("object")) {
            processObject(item, makeObjectName(itemName, typeAsSingular));
        }

        return getType(type, item, makeObjectName(itemName, typeAsSingular), schemaName, typeAsSingular);
    }

    private void processObject(Map<String, Object> item, String name) {
        var properties = (Map<String, Object>) item.get("properties");
        var required = (ArrayList<String>) item.get("required");
            var isRequired = (String key) -> required.contains(key);
        var params = new ArrayList<String>();
        var javadocs = new ArrayList<String>();

        for (var propName : properties.keySet()) {
            var prop = (Map<String, Object>) properties.get(propName);

            if (propName.equals("default")) {
                propName = propName + name;
            }

            params.add(processAndGetType(prop, propName, name, false) + " " + propName);
            javadocs.add("* @param " + propName + " " + prop.get("description"));
        }

        createJavaClass(item, name, javadocs, params);
    }

    private void createJavaClass(Map<String, Object> schema, String name, ArrayList<String> javadocs, ArrayList<String> params) {
        var format = """
                package io.bokun.octo;
                
                import java.util.ArrayList;
                
                /**
                * Octo DTO: %s.
                %s
                */
                
                public record %s (
                    %s
                ) {}
                """;

        createJavaFile(name, String.format(
                format,
                schema.containsKey("description") && !schema.get("description").equals("")
                        ? schema.get("description")
                        : name + " (auto-generated)",
                String.join("\n", javadocs),
                name,
                String.join(",\n    ", params)
        ));
    }

    private void createJavaEnum(String name, ArrayList<String> items) {
        var format = """
                package io.bokun.octo;
                
                /**
                * OCTO enum: %s
                */
                public enum %s {
                    %s
                }
                """;
        var itemFormat = "/**\n    * %s\n    */\n    %s";
        createJavaFile(name, String.format(
                format,
                name,
                name,
                String.join( ",\n\n    ", items.stream().map((item) -> String.format(itemFormat, item, item)).toList())
        ));
    }

    private void createJavaFile(String name, String content) {
        var file = new File(buildDir + "/generatedDTOs/src/main/java/" + name + ".java");
        try {
            Files.createDirectories(Paths.get(file.getParent()));
            Files.write(Path.of(file.getPath()), content.getBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void generate() {
        String octoFile = null;
        try {
            octoFile = Files.readString(Paths.get(filename), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        Map<String, Object> octoDefinition = new Yaml().load(octoFile);
        var octoSchemas = (Map<String, Object>) ((Map<String, Object>) octoDefinition.get("components")).get("schemas");
        for (var name : octoSchemas.keySet()) {
            var schema = (Map<String, Object>) octoSchemas.get(name);
            processAndGetType(schema, name, name, false);
        }

        createEnums();
    }
}