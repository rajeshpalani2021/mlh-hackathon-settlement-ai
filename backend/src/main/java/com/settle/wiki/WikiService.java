package com.settle.wiki;

import com.settle.config.SettleProperties;
import com.settle.nimble.NimbleService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Markdown knowledge wiki, loaded into memory. Pages have YAML-ish frontmatter (id, type, keys, title, ...).
 * Only code writes to it (writeRecipe, after a verified fix) - never the LLM.
 */
@Service
public class WikiService {
    private static final Logger log = LoggerFactory.getLogger(WikiService.class);

    public record Page(String id, String type, List<String> keys, String title, String body, String markdown,
                       Map<String, String> frontmatter) {}

    public record Hit(String id, String type, String title, String snippet) {}

    private final Path root;
    private volatile Map<String, Page> pages = Map.of();

    public WikiService(SettleProperties props) {
        this.root = Path.of(props.wikiDir()).toAbsolutePath().normalize();
    }

    @PostConstruct
    public synchronized void reload() {
        Map<String, Page> loaded = new LinkedHashMap<>();
        if (Files.isDirectory(root)) {
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(p -> p.toString().endsWith(".md")).sorted().forEach(p -> {
                    Page page = parse(p);
                    loaded.put(page.id(), page);
                });
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        } else {
            log.warn("Wiki directory {} not found", root);
        }
        pages = loaded;
        log.info("Wiki loaded: {} pages from {}", loaded.size(), root);
    }

    public List<Hit> search(String query) {
        List<String> terms = Arrays.stream(query.toUpperCase(Locale.ROOT).split("[^A-Z0-9+]+"))
                .filter(t -> !t.isBlank()).distinct().toList();
        if (terms.isEmpty()) return List.of();

        record Scored(Page page, int keyMatches, int score) {}
        List<Scored> scored = new ArrayList<>();
        for (Page p : pages.values()) {
            List<String> keys = p.keys().stream().map(k -> k.toUpperCase(Locale.ROOT)).toList();
            String title = p.title().toUpperCase(Locale.ROOT);
            String body = p.body().toUpperCase(Locale.ROOT);
            int keyMatches = 0;
            int score = 0;
            for (String t : terms) {
                if (keys.contains(t)) { keyMatches++; score += 10; }
                if (title.contains(t)) score += 3;
                if (body.contains(t)) score += 1;
            }
            if (score > 0) scored.add(new Scored(p, keyMatches, score));
        }
        // Recipes that match on keys first, then by exact key matches, then overall score.
        return scored.stream()
                .sorted(Comparator.comparing((Scored s) -> !(s.page().type().equals("recipe") && s.keyMatches() > 0))
                        .thenComparing(Scored::keyMatches, Comparator.reverseOrder())
                        .thenComparing(Scored::score, Comparator.reverseOrder()))
                .limit(5)
                .map(s -> new Hit(s.page().id(), s.page().type(), s.page().title(), snippet(s.page(), terms)))
                .toList();
    }

    public String read(String id) {
        Page p = pages.get(id.replaceAll("\\.md$", ""));
        return p == null ? null : p.markdown();
    }

    /** All recipe pages (verified past fixes). */
    public List<Page> recipes() {
        return pages.values().stream().filter(p -> p.type().equals("recipe")).toList();
    }

    public int count(String type) {
        return (int) pages.values().stream().filter(p -> p.type().equals(type)).count();
    }

    /** Writes recipes/CODE-FIELD-QUALIFIER.md. Called only by code after an ACKed fix. Returns the page id. */
    public synchronized String writeRecipe(String code, String field, String qualifier, String oldValue, String newValue,
                                           String body, String verifiedBy) {
        String name = (code + "-" + field + "-" + qualifier).replaceAll("[^A-Za-z0-9-]", "");
        String id = "recipes/" + name;
        // fix_* fields are machine-readable so code can apply the verified fix before sending (see RecipeAutoFixer)
        String md = """
                ---
                id: %s
                type: recipe
                keys: [%s, %s, %s, NAK]
                title: Recipe - %s on %s %s (verified fix)
                source: Written by Settle after the corrected message was ACKed
                created: %s
                verified_by: %s
                verified_at: %s
                fix_field: %s
                fix_qualifier: %s
                fix_old: %s
                fix_new: %s
                ---
                %s
                """.formatted(id, code, field, qualifier, code, field, qualifier, LocalDate.now(), verifiedBy,
                OffsetDateTime.now(ZoneOffset.UTC), field, qualifier, oldValue, newValue, body.strip());
        try {
            Path file = root.resolve("recipes").resolve(name + ".md");
            Files.createDirectories(file.getParent());
            Files.writeString(file, md, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        reload();
        return id;
    }

    /** Writes a draft wiki page for human review. Returns the page id. */
    public synchronized String draftPage(String code, String body, List<NimbleService.SearchResult> sources) {
        String name = code.replaceAll("[^A-Za-z0-9-]", "") + "-draft";
        String id = "drafts/" + name;
        String sourceList = sources == null ? "none" :
                sources.stream().map(NimbleService.SearchResult::url).collect(java.util.stream.Collectors.joining(", "));
        String md = """
                ---
                id: %s
                type: draft
                keys: [%s, DRAFT, NIMBLE]
                title: Draft - %s (pending review)
                source: Nimble web search
                created: %s
                status: draft
                sources: %s
                ---
                %s
                """.formatted(id, code, code, LocalDate.now(), sourceList, body.strip());
        try {
            Path file = root.resolve("drafts").resolve(name + ".md");
            Files.createDirectories(file.getParent());
            Files.writeString(file, md, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        reload();
        return id;
    }

    /** Demo reset: remove learned recipes. */
    public synchronized void deleteRecipes() {
        Path dir = root.resolve("recipes");
        if (Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.list(dir)) {
                for (Path p : files.filter(f -> f.toString().endsWith(".md")).toList()) Files.delete(p);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        reload();
    }

    private Page parse(Path file) {
        String md;
        try {
            md = Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String rel = root.relativize(file).toString().replace('\\', '/').replaceAll("\\.md$", "");
        Map<String, String> fm = new LinkedHashMap<>();
        String body = md;
        if (md.startsWith("---\n")) {
            int end = md.indexOf("\n---", 4);
            if (end > 0) {
                for (String line : md.substring(4, end).split("\n")) {
                    int c = line.indexOf(':');
                    if (c > 0) fm.put(line.substring(0, c).trim(), line.substring(c + 1).trim());
                }
                body = md.substring(Math.min(md.length(), end + 4)).strip();
            }
        }
        String keysRaw = fm.getOrDefault("keys", "").replaceAll("[\\[\\]]", "");
        List<String> keys = Arrays.stream(keysRaw.split(",")).map(String::trim).filter(k -> !k.isEmpty()).toList();
        return new Page(fm.getOrDefault("id", rel), fm.getOrDefault("type", "concept"), keys,
                fm.getOrDefault("title", rel), body, md, fm);
    }

    private static String snippet(Page p, List<String> terms) {
        for (String line : p.body().split("\n")) {
            String upper = line.toUpperCase(Locale.ROOT);
            if (!line.startsWith("#") && !line.isBlank() && terms.stream().anyMatch(upper::contains)) {
                return line.length() > 200 ? line.substring(0, 200) + "..." : line;
            }
        }
        String flat = p.body().replaceAll("\\s+", " ");
        return flat.length() > 200 ? flat.substring(0, 200) + "..." : flat;
    }
}
