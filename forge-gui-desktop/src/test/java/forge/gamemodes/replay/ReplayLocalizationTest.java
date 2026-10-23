package forge.gamemodes.replay;

import forge.util.Localizer;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.HashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.testng.Assert.*;

/** Shared replay text and locale coverage, without starting a UI or a match. */
public class ReplayLocalizationTest {
    private static final Pattern ARGUMENT = Pattern.compile("\\{(\\d+)(?:[,}])");

    private static Path languages() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            final Path candidate = directory.resolve("forge-gui/res/languages");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException("Cannot locate Forge language resources");
    }

    private static Properties properties(final String language) throws IOException {
        final Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(languages().resolve(language + ".properties"),
                StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    private static Set<String> arguments(final String text) {
        final Set<String> result = new HashSet<>();
        final Matcher matcher = ARGUMENT.matcher(text);
        while (matcher.find()) {
            result.add(matcher.group(1));
        }
        return result;
    }

    private static Localizer language(final String language) {
        final Localizer localizer = Localizer.getInstance();
        localizer.setEnglish(false);
        localizer.setLanguage(language, languages().toString());
        return localizer;
    }

    @AfterMethod
    public void restoreEnglish() {
        language("en-US");
    }

    @Test
    public void chineseContainsEveryEnglishKeyIncludingCaseSensitiveVariants() throws IOException {
        final Properties english = properties("en-US");
        final Properties chinese = properties("zh-CN");
        final Set<String> missing = new HashSet<>(english.stringPropertyNames());
        missing.removeAll(chinese.stringPropertyNames());
        assertTrue(missing.isEmpty(), "Missing Chinese keys: " + missing);
        for (final String key : english.stringPropertyNames()) {
            if (!english.getProperty(key).isBlank()) {
                assertFalse(chinese.getProperty(key).isBlank(), key);
            }
        }
    }

    @Test
    public void replayAndNewTranslationsHaveChineseTextAndMatchingArguments() throws IOException {
        final Properties english = properties("en-US");
        final Properties chinese = properties("zh-CN");
        for (final String key : english.stringPropertyNames()) {
            if (!(key.startsWith("lblReplay") || key.endsWith("RecordReplays")
                    || key.startsWith("advDifficulty") || key.startsWith("lblNetworkPicks")
                    || key.startsWith("lblNetworkPodSize") || key.startsWith("lblCommanderDraft")
                    || key.equals("lblChoosePartnerFor") || key.equals("lblSyncBulkCardDataConfirm"))) {
                continue;
            }
            final String en = english.getProperty(key);
            final String zh = chinese.getProperty(key);
            assertNotEquals(zh, en, key);
            assertTrue(zh.codePoints().anyMatch(c -> c >= 0x4e00 && c <= 0x9fff), key);
            assertEquals(arguments(zh), arguments(en), key);
            final Object[] values = {"ARG0", "ARG1", "ARG2", "ARG3", "ARG4", "ARG5"};
            final String result = new MessageFormat(zh, Locale.SIMPLIFIED_CHINESE).format(values);
            for (final String index : arguments(en)) {
                assertTrue(result.contains("ARG" + index), key + ": " + result);
            }
        }
    }

    @Test
    public void playbackMessagesAndUserNamesAreFormattedWithoutReinterpretingNames() {
        final Localizer localizer = language("zh-CN");
        assertEquals(localizer.getMessage("lblReplays"), "对局回放");
        assertEquals(localizer.getMessage("lblReplayPaused", 4), "回放已暂停。当前为 4 倍速。");
        assertEquals(localizer.getMessage("lblReplaySpeed", 8), "8 倍速");
        assertEquals(localizer.getMessage("lblReplayResume"), "继续播放");
        assertEquals(localizer.getMessage("lblReplayClose"), "关闭回放");
        final String name = "玩家 O'Brien {0} vs 小明";
        assertEquals(localizer.getMessage("lblReplayResult", name), "回放：" + name);
        assertEquals(localizer.getMessage("lblChoosePartnerFor", name), "为 " + name + " 选择伙伴指挥官");
        assertTrue(localizer.getMessage("lblSyncBulkCardDataConfirm", "25 MB").contains("25 MB"));
        assertTrue(localizer.getMessage("advDifficultySummaryEasy", "简单", "旅行靴").contains("\n"));
        assertEquals(localizer.getMessage("lblReplayVersus"), " 对阵 ");
        assertEquals(localizer.getMessage("lblReplayGameNumber", 2), "（第 2 局）");
        language("en-US");
        assertEquals(localizer.getMessage("lblReplayPlaying", 2), "Replaying. Speed 2x.");
        assertEquals(localizer.getMessage("lblReplayVersus"), " vs ");
    }

    private static Path header(final int magic, final int format, final String title) throws IOException {
        final Path file = Files.createTempFile("forge-replay-localization-", ".frp");
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(file))) {
            out.writeInt(magic);
            out.writeInt(format);
            out.writeUTF("test-build");
            out.writeLong(0);
            out.writeUTF(title);
        }
        return file;
    }

    @Test
    public void invalidHeadersUseLocalizedMessages() throws IOException {
        final Localizer localizer = language("zh-CN");
        final int[] magic = {0, ReplayStore.MAGIC};
        final int[] format = {ReplayStore.FORMAT, ReplayStore.FORMAT + 1};
        final String[] keys = {"lblReplayInvalidFile", "lblReplayIncompatibleFormat"};
        for (int i = 0; i < magic.length; i++) {
            final Path file = header(magic[i], format[i], "test");
            try {
                try (ReplayReader ignored = ReplayReader.open(file.toFile())) {
                    fail("Invalid replay header must be rejected");
                } catch (final IOException error) {
                    assertEquals(error.getMessage(), localizer.getMessage(keys[i]));
                }
            } finally {
                Files.deleteIfExists(file);
            }
        }
    }

    @Test
    public void existingReplayTitlesAndFormatRemainReadable() throws IOException {
        language("zh-CN");
        // Old recordings keep their stored title, including names that look like formatting syntax.
        final String title = "O'Brien {0} vs 小明 (game 2)";
        final Path file = header(ReplayStore.MAGIC, ReplayStore.FORMAT, title);
        try {
            try (ReplayReader reader = ReplayReader.open(file.toFile())) {
                assertEquals(reader.getTitle(), title);
                assertFalse(reader.next());
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
