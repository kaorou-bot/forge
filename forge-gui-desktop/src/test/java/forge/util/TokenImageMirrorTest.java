package forge.util;

import org.apache.commons.lang3.tuple.Pair;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.net.URI;
import java.util.List;

public class TokenImageMirrorTest {
    @Test
    public void communityMirrorKeepsOriginalSourceAsFallback() {
        final String name = "TDLS/1_b_0_1_thrull.jpg";
        final String original = "https://oldborder-shandalar.net/tokens/" + name;
        final List<String> urls = ImageFetcher.getTokenImageSources(name, List.of(Pair.of(name, original)));
        Assert.assertEquals(urls.size(), 2);
        Assert.assertEquals(urls.get(0), ForgeUpdateConfig.getTokenImageDownloadUrl(name));
        Assert.assertEquals(urls.get(1), original);
        Assert.assertTrue(urls.get(0).contains("/tokens/tokens-20260922-181200/"));
        Assert.assertFalse(urls.get(0).contains("/cards/"));
        Assert.assertTrue(ForgeUpdateConfig.isTokenImageMirrorUrl(urls.get(0)));
        Assert.assertFalse(ForgeUpdateConfig.isTokenImageMirrorUrl(original));
        Assert.assertFalse(ForgeUpdateConfig.isTokenImageMirrorUrl(null));
    }

    @Test
    public void missingLegacyMappingStillTriesMirror() {
        final String name = "2XM/1_c_0_1_eldrazi_spawn_sac.jpg";
        Assert.assertEquals(ImageFetcher.getTokenImageSources(name, List.of()),
                List.of(ForgeUpdateConfig.getTokenImageDownloadUrl(name)));
    }

    @Test
    public void unicodeBackFaceAndSpecialCharactersAreEncodedWithoutRenaming() {
        final String name = "MID/21☇_night.jpg";
        final String url = ForgeUpdateConfig.getTokenImageDownloadUrl(name);
        Assert.assertTrue(url.endsWith("MID/21%E2%98%87_night.jpg"));
        Assert.assertTrue(URI.create(url).getPath().endsWith(name));
        Assert.assertFalse(url.contains(".full"));
        final String special = "SET/1_a b+%#.jpg";
        Assert.assertTrue(URI.create(ForgeUpdateConfig.getTokenImageDownloadUrl(special)).getPath().endsWith(special));
    }

    @Test
    public void distinctCollectorNumbersStayDistinct() {
        Assert.assertNotEquals(ForgeUpdateConfig.getTokenImageDownloadUrl("F17/10☇_c_a_treasure_sac.jpg"),
                ForgeUpdateConfig.getTokenImageDownloadUrl("F17/11☇_c_a_treasure_sac.jpg"));
        Assert.assertEquals(ForgeUpdateConfig.getTokenImageDownloadUrl(null), "");
        Assert.assertEquals(ForgeUpdateConfig.getTokenImageDownloadUrl(""), "");
    }
}
