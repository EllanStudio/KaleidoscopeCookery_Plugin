package net.kaleidoscope.cookery.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftEngineFoodComponentMigratorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void convertsModifierToAbsoluteSaturationAndCreatesBackup() throws IOException {
        Path file = writeYaml("""
                items:
                  cook:test:
                    data:
                      components:
                        minecraft:food:
                          nutrition: 9
                          saturation_modifier: 0.611 # legacy value
                          can_always_eat: true
                """);
        String original = Files.readString(file);

        CraftEngineFoodComponentMigrator.MigrationReport report =
                CraftEngineFoodComponentMigrator.migrate(temporaryDirectory);

        assertEquals(1, report.filesScanned());
        assertEquals(1, report.filesChanged());
        assertEquals(1, report.componentsChanged());
        assertEquals(0, report.componentsSkipped());
        assertTrue(Files.readString(file).contains("saturation: 10.998 # legacy value"));
        assertFalse(Files.readString(file).contains("saturation_modifier"));
        assertEquals(original, Files.readString(backupOf(file)));
    }

    @Test
    void migrationIsIdempotent() throws IOException {
        Path file = writeYaml("""
                items:
                  cook:test:
                    data:
                      components:
                        minecraft:food:
                          nutrition: 5
                          saturation_modifier: 0.6
                """);

        CraftEngineFoodComponentMigrator.migrate(temporaryDirectory);
        String migrated = Files.readString(file);
        CraftEngineFoodComponentMigrator.MigrationReport second =
                CraftEngineFoodComponentMigrator.migrate(temporaryDirectory);

        assertEquals(0, second.filesChanged());
        assertEquals(0, second.componentsChanged());
        assertEquals(migrated, Files.readString(file));
        assertTrue(migrated.contains("saturation: 6"));
    }

    @Test
    void ignoresUnrelatedKeysAndSkipsAmbiguousComponents() throws IOException {
        Path file = writeYaml("""
                unrelated:
                  saturation_modifier: 0.5
                items:
                  cook:missing_nutrition:
                    components:
                      minecraft:food:
                        saturation_modifier: 0.8
                  cook:already_migrated:
                    components:
                      minecraft:food:
                        nutrition: 4
                        saturation_modifier: 0.8
                        saturation: 6.4
                """);

        CraftEngineFoodComponentMigrator.MigrationReport report =
                CraftEngineFoodComponentMigrator.migrate(temporaryDirectory);

        assertEquals(0, report.filesChanged());
        assertEquals(0, report.componentsChanged());
        assertEquals(2, report.componentsSkipped());
        assertEquals(3, count(Files.readString(file), "saturation_modifier"));
        assertFalse(Files.exists(backupOf(file)));
    }

    @Test
    void preservesUtf8BomAndCrLfLineEndings() throws IOException {
        Path file = temporaryDirectory.resolve("food.yml");
        String yaml = "\uFEFFitems:\r\n"
                + "  cook:test:\r\n"
                + "    components:\r\n"
                + "      minecraft:food:\r\n"
                + "        nutrition: 2\r\n"
                + "        saturation_modifier: 0.3\r\n";
        Files.writeString(file, yaml, StandardCharsets.UTF_8);

        CraftEngineFoodComponentMigrator.migrate(temporaryDirectory);

        String migrated = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(migrated.startsWith("\uFEFF"));
        assertTrue(migrated.contains("saturation: 1.2\r\n"));
        assertFalse(migrated.replace("\r\n", "").contains("\n"));
    }

    private Path writeYaml(String contents) throws IOException {
        Path file = temporaryDirectory.resolve("food.yml");
        Files.writeString(file, contents, StandardCharsets.UTF_8);
        return file;
    }

    private static Path backupOf(Path file) {
        return file.resolveSibling(file.getFileName() + ".pre-saturation-migration.bak");
    }

    private static int count(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
