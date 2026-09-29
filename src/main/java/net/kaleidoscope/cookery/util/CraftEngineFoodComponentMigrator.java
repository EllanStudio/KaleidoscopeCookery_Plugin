package net.kaleidoscope.cookery.util;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Migrates the pre-release KaleidoscopeCookery food definitions to Minecraft's
 * absolute {@code minecraft:food.saturation} format.
 */
public final class CraftEngineFoodComponentMigrator {
    private static final String BACKUP_SUFFIX = ".pre-saturation-migration.bak";
    private static final Pattern FOOD_COMPONENT = Pattern.compile("^(\\s*)minecraft:food:\\s*(?:#.*)?$");
    private static final String NUMBER = "[-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][-+]?\\d+)?";
    private static final Pattern NUTRITION = Pattern.compile(
            "^(\\s*)nutrition:\\s*(" + NUMBER + ")\\s*(?:#.*)?$");
    private static final Pattern LEGACY_SATURATION = Pattern.compile(
            "^(\\s*)saturation_modifier:\\s*(" + NUMBER + ")(\\s*(?:#.*)?)$");
    private static final Pattern ABSOLUTE_SATURATION = Pattern.compile(
            "^\\s*saturation:\\s*" + NUMBER + "\\s*(?:#.*)?$");

    private CraftEngineFoodComponentMigrator() {
    }

    public static MigrationReport migrate(Path configurationDirectory) throws IOException {
        if (!Files.isDirectory(configurationDirectory)) {
            return new MigrationReport(0, 0, 0, 0);
        }

        List<Path> yamlFiles;
        try (Stream<Path> paths = Files.walk(configurationDirectory)) {
            yamlFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(CraftEngineFoodComponentMigrator::isYamlFile)
                    .sorted(Comparator.naturalOrder())
                    .toList();
        }

        int filesChanged = 0;
        int componentsChanged = 0;
        int componentsSkipped = 0;
        for (Path yamlFile : yamlFiles) {
            FileMigration migration = migrateFile(yamlFile);
            if (migration.changed()) {
                filesChanged++;
                componentsChanged += migration.componentsChanged();
            }
            componentsSkipped += migration.componentsSkipped();
        }
        return new MigrationReport(yamlFiles.size(), filesChanged, componentsChanged, componentsSkipped);
    }

    private static boolean isYamlFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".yml") || name.endsWith(".yaml");
    }

    private static FileMigration migrateFile(Path yamlFile) throws IOException {
        byte[] originalBytes = Files.readAllBytes(yamlFile);
        String original = new String(originalBytes, StandardCharsets.UTF_8);
        boolean hasBom = original.startsWith("\uFEFF");
        String content = hasBom ? original.substring(1) : original;
        String lineSeparator = content.contains("\r\n") ? "\r\n" : "\n";
        List<String> lines = new ArrayList<>(List.of(content.split("\\r?\\n", -1)));

        int changed = 0;
        int skipped = 0;
        for (int index = 0; index < lines.size(); index++) {
            Matcher foodMatcher = FOOD_COMPONENT.matcher(lines.get(index));
            if (!foodMatcher.matches()) {
                continue;
            }

            int foodIndent = foodMatcher.group(1).length();
            int blockEnd = findBlockEnd(lines, index + 1, foodIndent);
            BlockMigration blockMigration = migrateFoodBlock(lines, index + 1, blockEnd, foodIndent);
            changed += blockMigration.changed() ? 1 : 0;
            skipped += blockMigration.skipped() ? 1 : 0;
            index = blockEnd - 1;
        }

        if (changed == 0) {
            return new FileMigration(false, 0, skipped);
        }

        String migrated = String.join(lineSeparator, lines);
        if (hasBom) {
            migrated = "\uFEFF" + migrated;
        }
        writeWithBackup(yamlFile, originalBytes, migrated.getBytes(StandardCharsets.UTF_8));
        return new FileMigration(true, changed, skipped);
    }

    private static int findBlockEnd(List<String> lines, int start, int parentIndent) {
        int index = start;
        while (index < lines.size()) {
            String line = lines.get(index);
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#") && leadingWhitespace(line) <= parentIndent) {
                break;
            }
            index++;
        }
        return index;
    }

    private static BlockMigration migrateFoodBlock(
            List<String> lines,
            int start,
            int end,
            int parentIndent
    ) {
        BigDecimal nutrition = null;
        int legacyLine = -1;
        BigDecimal modifier = null;
        boolean hasAbsoluteSaturation = false;

        for (int index = start; index < end; index++) {
            String line = lines.get(index);
            if (leadingWhitespace(line) <= parentIndent) {
                continue;
            }

            Matcher nutritionMatcher = NUTRITION.matcher(line);
            if (nutritionMatcher.matches()) {
                nutrition = new BigDecimal(nutritionMatcher.group(2));
            }

            Matcher legacyMatcher = LEGACY_SATURATION.matcher(line);
            if (legacyMatcher.matches()) {
                legacyLine = index;
                modifier = new BigDecimal(legacyMatcher.group(2));
            }

            if (ABSOLUTE_SATURATION.matcher(line).matches()) {
                hasAbsoluteSaturation = true;
            }
        }

        if (legacyLine < 0) {
            return new BlockMigration(false, false);
        }
        if (nutrition == null || modifier == null || hasAbsoluteSaturation) {
            return new BlockMigration(false, true);
        }

        Matcher legacyMatcher = LEGACY_SATURATION.matcher(lines.get(legacyLine));
        if (!legacyMatcher.matches()) {
            return new BlockMigration(false, true);
        }
        BigDecimal saturation = nutrition
                .multiply(modifier)
                .multiply(BigDecimal.valueOf(2))
                .stripTrailingZeros();
        String formatted = saturation.signum() == 0 ? "0" : saturation.toPlainString();
        lines.set(legacyLine, legacyMatcher.group(1) + "saturation: " + formatted + legacyMatcher.group(3));
        return new BlockMigration(true, false);
    }

    private static int leadingWhitespace(String line) {
        int index = 0;
        while (index < line.length() && Character.isWhitespace(line.charAt(index))) {
            index++;
        }
        return index;
    }

    private static void writeWithBackup(Path file, byte[] originalBytes, byte[] migratedBytes) throws IOException {
        Path backup = file.resolveSibling(file.getFileName() + BACKUP_SUFFIX);
        if (Files.notExists(backup)) {
            Files.write(backup, originalBytes);
        }

        Path temporary = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".migration.tmp");
        try {
            Files.write(temporary, migratedBytes);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public record MigrationReport(
            int filesScanned,
            int filesChanged,
            int componentsChanged,
            int componentsSkipped
    ) {
    }

    private record FileMigration(boolean changed, int componentsChanged, int componentsSkipped) {
    }

    private record BlockMigration(boolean changed, boolean skipped) {
    }
}
