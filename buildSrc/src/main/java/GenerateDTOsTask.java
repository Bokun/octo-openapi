package io.bokun.octo.gradle;

import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Predicate;

/**
 * Generates DTOs & enums for the java compiler to compile them
 */
public class GenerateDTOsTask {
    private final String filename;
    private final String buildDir;

    /**
     * Key: name of the enum, value: list of all enum items
     */
    private final Map<String,ArrayList<String>> enums = new HashMap<>();

    public GenerateDTOsTask(String filename, String buildDir) {
        this.filename = filename;
        this.buildDir = buildDir;
    }

    /**
     * Entry point from Gradle build script
     *
     * @param filename the filename of the OpenAPI spec we're using
     * @param buildDir the output directory
     */
    public static void generateDTOs(String filename, String buildDir) {
        var instance = new io.bokun.octo.gradle.GenerateDTOsTask(filename, buildDir);
        instance.generate();
    }

    /**
     * Type information from the OpenAPI spec, non-nullable is generated as @Nonnull, required is so far unused
     */
    private static class Type {
        public String type;
        public Boolean nullable = false;
        public Boolean required = false;

        public Type(String type) {
            this.type = type;
        }

        public Type nullable(boolean nullable) {
            this.nullable = nullable;
            return this;
        }

        public Type required(boolean required) {
            this.required = required;
            return this;
        }

        public Type wrap(String wrapClass) {
            return new Type(wrapClass + "<" + type + ">").nullable(nullable).required(required);
        }

        public String toString() {
            return (nullable || !required ? "" : "@Nonnull ") + type;
        }
    }

    /**
     * The same enum might be defined a few dozen places in the API spec, this function is
     * called for each time it shows up. It registers the enum for an enum file to be generated
     * from it later on.
     *
     * @param name Name of the enum
     * @param options all items of the enum
     */
    private void registerEnum(String name, ArrayList<String> options) {
        if (name == null || options == null) {
            return;
        }

        enums.put(name, options);
    }

    /**
     * Triggers creation of a .java file for each registered enum
     */
    private void createEnums() {
        for(var entry : enums.entrySet()) {
            createJavaEnum(entry.getKey(), entry.getValue(), null);
        }
    }

    /**
     * Capitalizes the input string
     */
    private String capitalize(String input) {
        return input.substring(0, 1).toUpperCase() + input.substring(1);
    }

    /**
     * returns a capitalized version of the input string & converts it to the singular form if applicable.
     * Example: from an Array called "units", it would create "Unit", which can then be used to create ArrayList<Unit>
     */
    private String makeObjectName(String input, Boolean typeAsSingular) {
        input = capitalize(input);

        // openingHours[] should be ArrayList<OpeningHours>, not ArrayList<OpeningHour>
        if (!typeAsSingular || input.toLowerCase().equals("openinghours")) {
            return input;
        }

        if (input.endsWith("s")) {
            return input.substring(0, input.length() - 1);
        }

        return input;
    }

    /**
     * returns the correct Java type for the given OpenAPI spec type.
     * If the input is an array, it returns an ArrayList of the type inside the array
     */
    private Type getType(
            String type,
            Map<String, Object> prop,
            String propName,
            String schemaName,
            Boolean typeAsSingular,
            Predicate<String> isRequired,
            String pathPrefix
    ) {
        if (type.equals("array")) {
            var items = (Map<String, Object>) prop.get("items");
            return processAndGetType(items, propName, schemaName, true, isRequired, pathPrefix).wrap("ArrayList");
        }

        // Restrictions show up twice and the corresponding classes are called UnitRestrictions and OptionRestrictions
        if (type.equals("object") && propName.equals("restrictions")) {
            propName = schemaName + capitalize(propName);
        }

        // Some UUID fields are not marked as UUID
        if (type.equals("string") && propName.equals("uuid")) {
            return new Type("UUID").required(isRequired.test(propName));
        }

        if (type.equals("string") && prop.containsKey("format")) {
            return switch((String) prop.get("format")) {
                case "uri" -> new Type("URL").required(isRequired.test(propName));
                case "email" -> new Type("String").required(isRequired.test(propName));
                case "date-time" -> new Type("ZonedDateTime").required(isRequired.test(propName));
                case "date" -> new Type("LocalDate").required(isRequired.test(propName));
                case "uuid" -> new Type("UUID").required(isRequired.test(propName));
                default -> throw new RuntimeException("Format detected: " + prop.get("format"));
            };
        }

        if (propName.equals("expirationMinutes")) {
            return new Type("Integer").required(isRequired.test(propName));
        }

        if (propName.equals("availabilityId") && schemaName.equals("PostBookings")) {
            return new Type("String").required(false).nullable(true);
        }

        String[] zonedDateTimeFields = {
                "utcCreatedAt",
                "utcUpdatedAt",
                "utcExpiresAt",
                "utcRedeemedAt",
                "utcConfirmedAt",
                "utcCutoffAt",
                "utcCancelledAt",
                "localDateTimeStart",
                "localDateTimeEnd",
        };

        if (type.equals("string") && Arrays.asList(zonedDateTimeFields).contains(propName)) {
            return new Type("ZonedDateTime").required(isRequired.test(propName));
        }

        return switch (type) {
            case "string" -> new Type("String").required(isRequired.test(propName));
            case "boolean" -> new Type("Boolean").required(isRequired.test(propName));
            case "integer" -> new Type("Integer").required(isRequired.test(propName));
            case "object" -> new Type(makeObjectName(propName, typeAsSingular)).required(isRequired.test(propName));
            default ->
                    throw new RuntimeException("getType: " + propName + ": Unknown type: " + type + " " + prop.toString());
        };
    }

    /**
     * Inspects the given object, how the type info is encoded in it, and passes it on to one of the three other versions of this function
     */
    private Type processAndGetType(
            Map<String, Object> item,
            String itemName,
            String schemaName,
            Boolean typeAsSingular,
            Predicate<String> isRequired,
            String pathPrefix
    ) {
        if (item.containsKey("oneOf")) {
            return processAndGetTypeOneOf(item, itemName, schemaName, typeAsSingular, isRequired, pathPrefix);
        }

        var type = item.get("type");

        if (type instanceof String) {
            return processAndGetTypeSingleType((String) type, item, itemName, schemaName, typeAsSingular, isRequired, pathPrefix);
        } else if (type instanceof ArrayList<?>) {
            return processAndGetTypeArrayOfTypes((ArrayList<?>) type, item, itemName, schemaName, typeAsSingular, isRequired, pathPrefix);
        }

        throw new RuntimeException("processAndGetType: " + itemName + ": Unknown type: " + (type != null ? type.getClass() : "null") + " " + item);
    }

    /**
     * There are multiple ways of having "either X or Y" type definitions in the OCTO OpenAPI spec.
     * This function handles the type, where it's:
     * {
     * "oneOf": [
     * {"type": "null"},
     * {... inline object definition ...}
     * ]
     * }
     */
    private Type processAndGetTypeOneOf(
            Map<String, Object> item,
            String itemName,
            String schemaName,
            Boolean typeAsSingular,
            Predicate<String> isRequired,
            String pathPrefix
    ) {
        boolean nullable = false;
        Map<String, Object> foundChild = null;

        for (var child : (ArrayList<Map<String, Object>>) item.get("oneOf")) {
            if (!child.containsKey("type")) {
                continue;
            }

            if (child.get("type").equals("null")) {
                nullable = true;
                continue;
            }

            foundChild = child;
        }

        if (foundChild == null) {
            throw new RuntimeException("processAndGetType: " + itemName + ": Unparseable oneOf: no child found");
        }

        return processAndGetType(foundChild, itemName, schemaName, typeAsSingular, isRequired, pathPrefix).nullable(nullable);
    }

    /**
     * There are multiple ways of having "either X or Y" type definitions in the OCTO OpenAPI spec.
     * This function handles the type, where it's ["null", "String"] or ["null", ... some Object name ... ]
     */
    private Type processAndGetTypeArrayOfTypes(
            ArrayList<?> type,
            Map<String, Object> item,
            String itemName,
            String schemaName,
            Boolean typeAsSingular,
            Predicate<String> isRequired,
            String pathPrefix
    ) {
        String foundType = null;
        Boolean nullable = false;

        for (var t : type) {
            if (t.equals("null")) {
                nullable = true;
                continue;
            }

            if (!(t instanceof String))
                continue;

            foundType = (String) t;
        }

        if (foundType == null) {
            throw new RuntimeException("processAndGetType: Can't find suitable type for " + itemName);
        }

        return processAndGetTypeSingleType(foundType, item, itemName, schemaName, typeAsSingular, isRequired, pathPrefix).nullable(nullable);
    }

    /**
     * processes the entity (registers included objects & enums) and returns the resulting type
     * (e.g. class name or enum name) for it to be used in the calling function.
     */
    private Type processAndGetTypeSingleType(
            String type,
            Map<String, Object> item,
            String itemName,
            String schemaName,
            Boolean typeAsSingular,
            Predicate<String> isRequired,
            String pathPrefix
    ) {
        String objectName = makeObjectName(itemName, typeAsSingular);

        // The availability inside a booking is an abbreviated version of the availability object
        if (objectName.equals("Availability") && schemaName.equals("Booking")) {
            itemName = schemaName + capitalize(itemName);
        }

        if (objectName.equals("Unit") && Set.of("PostAvailabilityCalendar", "PostAvailability").contains(schemaName)) {
            itemName = "AvailabilityRequestUnit";
        }

        if (objectName.equals("UnitItem") && schemaName.equals("PostBookings")) {
            itemName = "ReservationRequestUnitItem";
        }

        if (objectName.equals("UnitItem") && Set.of("PostBookingsUuidConfirm", "PatchBookingsUuid").contains(schemaName)) {
            itemName = "BookingRequestUnitItem";
        }

        if (objectName.equals("Contact") && schemaName.equals("Supplier")) {
            itemName = "SupplierContact";
        }

        // Tickets and vouchers are the same things, same type, just different name in Octo depending on where it's used.
        // Let's not be part of this bs and have them be one type
        if (objectName.equals("Voucher")) {
            itemName = "Ticket";
        }

        if (type.equals("object")) {
            processObject(item, makeObjectName(itemName, typeAsSingular), pathPrefix);
        }

        String title = (String) item.get("title");
        var options = (ArrayList<String>) item.get("enum");

        if (type.equals("string") && itemName.equals("refund") && schemaName.equals("Cancellation")) {
            title = "CancellationRefund";
            options = new ArrayList<>(List.of("FULL", "PARTIAL", "NONE"));
        }

        if (type.equals("string") && title != null) {
            itemName = title;
            type = "object";
            registerEnum(itemName, options);
        }

        return getType(type, item, itemName, schemaName, typeAsSingular, isRequired, pathPrefix);
    }

    /**
     * When the type of something is "object", we register it as a new schema. This is the method doing that.
     */
    private void processObject(Map<String, Object> item, String name, String pathPrefix) {
        var properties = (Map<String, Object>) item.get("properties");
        var required = (ArrayList<String>) item.get("required");
        Predicate<String> isRequired = (String key) -> required == null ? false : required.contains(key);
        var params = new ArrayList<String>();
        var javadocs = new ArrayList<String>();

        for (var propName : properties.keySet()) {
            var prop = (Map<String, Object>) properties.get(propName);
            var annotation = "";

            if (propName.equals("default")) {
                propName = propName + name;
                annotation = "@SerializedName(\"default\") ";
            }

            params.add(annotation + processAndGetType(prop, propName, name, false, isRequired, null) + " " + propName);
            javadocs.add(" * @param " + propName + " " + (prop.containsKey("description") && !prop.get("description").equals("") ? prop.get("description") : propName));
        }

        if (name.equals("PostAvailability")) {
            params.add("LocalDate localDate");
            javadocs.add(" * @param localDate localDate - if set, it's the same as localDateStart and localDateEnd being set to the same value");
        }

        createJavaClass(item, name, javadocs, params, pathPrefix);
    }

    /**
     * Creates a java class out of the schema & generated parameters and javadoc code
     */
    private void createJavaClass(Map<String, Object> schema,
                                 String name,
                                 ArrayList<String> javadocs,
                                 ArrayList<String> params,
                                 String pathPrefix) {
        // Restrictions is something we don't use, we use UnitRestrictions and OptionRestrictions
        if (Objects.equals(name, "Restrictions")) {
            return;
        }
        var format = """
                package io.bokun.octo%s;
                
                import java.net.URL;
                import java.time.LocalDate;
                import java.time.ZonedDateTime;
                import java.util.ArrayList;
                import java.util.UUID;
                import javax.annotation.Nonnull;
                import com.google.gson.annotations.SerializedName;
                %s
                
                /**
                 * Octo DTO: %s.
                %s
                 */
                
                public record %s (
                    %s
                ) {}
                """;

        var pkg = pathPrefix == null ? "" : "." + pathPrefix;
        var pkgImport = pathPrefix == null ? "" : "import io.bokun.octo.*;";

        createJavaFile(name, String.format(
                format,
                pkg,
                pkgImport,
                schema.containsKey("description") && !schema.get("description").equals("")
                        ? schema.get("description")
                        : name + " (auto-generated)",
                String.join("\n", javadocs),
                name,
                String.join(",\n    ", params)
        ), pathPrefix);
    }

    /**
     * Creates a java enum out of the name & list of items.
     */
    private void createJavaEnum(String name, ArrayList<String> items, String pathPrefix) {
        var format = """
                package io.bokun.octo;
                
                /**
                 * OCTO enum: %s
                 */
                public enum %s {
                    %s
                }
                """;
        var itemFormat = "/**\n     * %s\n     */\n    %s";
        createJavaFile(name, String.format(
                format,
                name,
                name,
                String.join( ",\n\n    ", items.stream().map((item) -> String.format(itemFormat, item, item)).toList())
        ), pathPrefix);
    }

    /**
     * Saves a java class or object to disk
     */
    private void createJavaFile(String name, String content, String pathPrefix) {
        var folder = pathPrefix == null ? "" : pathPrefix + "/";
        var file = new File(buildDir + "/generatedDTOs/src/main/java/" + folder + name + ".java");
        try {
            Files.createDirectories(Paths.get(file.getParent()));
            Files.write(Path.of(file.getPath()), content.getBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Creates a DTO name from a request path name, e.g. GET /products/{id} -> RequestBodyGETProductsID
     */
    private String createDTONameFromPath(String path, String method) {
        int i = 0;
        boolean capitalize = true;
        StringBuilder ret = new StringBuilder(capitalize(method));

        while (i < path.length()) {

            var current = path.charAt(i);
            var toAppend = capitalize ? Character.toUpperCase(current) : current;
            capitalize = false;
            i++;

            if (current == '/' || current == '{') {
                capitalize = true;
                continue;
            }

            if (current == '}') {
                continue;
            }

            ret.append(toAppend);
        }

        return ret.toString();
    }

    /**
     * Reads the YAML file and initializes the processing of its contents
     */
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
            processAndGetType(schema, name, name, false, (String n) -> false, null);
        }

        var octoPaths = (Map<String, Object>) octoDefinition.get("paths");
        for (var path : octoPaths.keySet()) {
            var requestMethods = (Map<String, Object>) octoPaths.get(path);
            for (var method : requestMethods.keySet()) {
                if (method.toLowerCase().equals("get") || method.toLowerCase().equals("parameters")) {
                    continue;
                }
                var dtoName = createDTONameFromPath(path, method);
                var methodObject = (Map<String, Object>) requestMethods.get(method);
                var requestBody = (Map<String, Object>) methodObject.get("requestBody");
                var content = (Map<String, Object>) requestBody.get("content");
                var applicationJson = (Map<String, Object>) content.get("application/json");
                var schema = (Map<String, Object>) applicationJson.get("schema");
                processAndGetType(schema, dtoName, dtoName, false, (String n) -> false, "requestBody");
            }
        }

        createEnums();
    }
}