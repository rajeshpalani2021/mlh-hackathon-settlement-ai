package com.settle;

import com.settle.config.SettleProperties;
import com.settle.wiki.WikiService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WikiServiceTest {

    @Test
    void searchesRealWikiAndRanksRecipesFirst(@TempDir Path tmp) throws IOException {
        // copy the repo wiki (../wiki relative to backend/) into a temp dir so tests never touch it
        Path src = Path.of("../wiki");
        try (var files = Files.walk(src)) {
            for (Path p : files.toList()) {
                Path dest = tmp.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(dest);
                else Files.copy(p, dest);
            }
        }
        WikiService wiki = new WikiService(new SettleProperties(tmp.toString(), 10, 30, false, null));
        wiki.reload();

        List<WikiService.Hit> hits = wiki.search("T27");
        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).id()).isEqualTo("codes/nak-T27");
        assertThat(wiki.read("codes/nak-T27")).contains("DTCYUS33");

        String id = wiki.writeRecipe("T27", "95P", "PSET", "Fix PSET DTCYUS3 -> DTCYUS33", "HCK541000000002");
        assertThat(id).isEqualTo("recipes/T27-95P-PSET");
        hits = wiki.search("NAK T27 PSET");
        assertThat(hits.get(0).id()).isEqualTo("recipes/T27-95P-PSET");
        assertThat(hits.get(0).type()).isEqualTo("recipe");
        assertThat(wiki.read("recipes/T27-95P-PSET")).contains("verified_by: HCK541000000002");

        wiki.deleteRecipes();
        assertThat(wiki.count("recipe")).isZero();
        assertThat(wiki.search("LACK").get(0).id()).isEqualTo("reasons/LACK");
    }
}
