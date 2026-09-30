package com.example.chatreader.importer.infrastructure;

import com.example.chatreader.importer.domain.ChatImporter;
import com.example.chatreader.importer.domain.DateTimes;
import com.example.chatreader.importer.domain.ImportException;
import com.example.chatreader.importer.domain.ImportFormat;
import com.example.chatreader.importer.domain.ImportSource;
import com.example.chatreader.importer.domain.NormalizedChat;
import com.example.chatreader.importer.domain.RoleMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Adapter de Markdown — o formato que o usuario mesmo escreve a mao para arquivar
 * conversa. Nao e um exportador especifico: e um container simples, definido aqui.
 *
 * <p>Schema (v1), documentado em {@code docs/importers.md}:
 * <pre>
 * ---
 * id: spring-transactional
 * title: Spring Transactional
 * createdAt: 2026-09-20T14:00:00Z
 * updatedAt: 2026-09-27T18:30:00Z
 * tags: [Java, Spring, Backend]
 * ---
 *
 * ## VOCE
 *
 * Como funciona o {@code @Transactional}?
 *
 * <!-- 2026-09-20T14:00:00Z -->
 *
 * ## CHATGPT
 *
 * Ele delega ao {@code TransactionManager}.
 * </pre>
 *
 * <p>Regras, escolhidas para o arquivo continuar legivel como Markdown no Obsidian e no
 * Git (que e onde esse formato vai nascer):
 * <ul>
 *   <li>front-matter opcional, entre dois {@code ---}, com {@code chave: valor} plano;
 *   <li>cada mensagem comeca num titulo de nivel 2 cujo texto e' o papel, lido pelo
 *       {@link RoleMapper} — {@code ## VOCE}, {@code ## CHATGPT}, {@code ## system}...;
 *   <li>o corpo do titulo ate o proximo titulo de nivel 2 e' o conteudo, guardado
 *       <b>cru</b>: o backend nao renderiza Markdown;
 *   <li>data da mensagem e' opcional, num comentario HTML {@code <!-- ISO -->};
 *   <li>o conteudo e' Markdown do usuario e nao pode conter {@code ---} sozinho na
 *       primeira linha de um bloco nem um {@code ##} que nao seja papel — por isso
 *       a divisao de mensagens e feita so por {@code ##}.
 * </ul>
 *
 * <p>Um arquivo pode ter varias conversas, separadas por um titulo de nivel 1
 * ({@code #}); cada bloco com seu proprio front-matter e' uma conversa. Conversas sem
 * titulo de nivel 1 sao um unico chat, que e' o caso comum.
 *
 * <p>Este adapter e <b>puro</b>: le bytes e devolve {@link NormalizedChat}.
 */
@Component
public class MarkdownChatImporter implements ChatImporter {

    private static final String FRONT_MATTER_FENCE = "---";
    private static final String H2 = "## ";

    @Override
    public ImportFormat format() {
        return ImportFormat.MARKDOWN;
    }

    @Override
    public boolean supports(ImportSource source) {
        var text = source.asString();
        if (text.stripLeading().startsWith("{")) {
            return false; // e' JSON: quem cuida disso e' o JsonChatImporter
        }
        try {
            var blocks = splitBlocks(text);
            return !blocks.isEmpty() && blocks.stream().anyMatch(b -> !b.messages().isEmpty());
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public List<NormalizedChat> importChats(ImportSource source) {
        var text = source.asString();
        var result = new ArrayList<NormalizedChat>();
        for (var block : splitBlocks(text)) {
            var chat = toChat(block);
            if (chat != null) {
                result.add(chat);
            }
        }
        if (result.isEmpty()) {
            throw new ImportException(
                    "Nenhuma conversa encontrada. Esperado front-matter (---) e mensagens "
                            + "comecando com '## PAPEL'.");
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // divisao do arquivo em conversas
    // -----------------------------------------------------------------------

    private record Block(Map<String, String> frontMatter, String title, List<Section> messages) {
    }

    private record Section(String role, String body, Instant createdAt) {
    }

    /**
     * Corta o arquivo em blocos de conversa. Um {@code #} de nivel 1 fecha o bloco
     * anterior; o primeiro bloco pode nao ter {@code #} (front-matter da linha 1).
     */
    private List<Block> splitBlocks(String text) {
        var lines = stripBom(text).split("\r?\n", -1);
        var blocks = new ArrayList<Block>();
        Map<String, String> frontMatter = new LinkedHashMap<>();
        var title = (String) null;
        var sections = new ArrayList<Section>();
        var role = (String) null;
        var body = new StringBuilder();

        // front-matter do primeiro bloco
        var start = readFrontMatter(lines, 0, frontMatter);

        for (var i = start; i <= lines.length; i++) {
            var line = i < lines.length ? lines[i] : null;

            if (line != null && isH1(line)) {
                if (!sections.isEmpty() || title != null) {
                    blocks.add(new Block(frontMatter, title, closeSections(sections, role, body)));
                }
                frontMatter = new LinkedHashMap<>();
                title = line.replaceFirst("^#+\\s*", "").strip();
                sections = new ArrayList<>();
                role = null;
                body = new StringBuilder();
                // um # pode abrir um novo bloco com o proprio front-matter
                var resumed = readFrontMatter(lines, i + 1, frontMatter);
                if (resumed > i + 1) {
                    i = resumed - 1;
                }
                continue;
            }

            if (line != null && line.startsWith(H2)) {
                if (role != null) {
                    sections.add(section(role, body.toString()));
                }
                role = line.substring(H2.length()).strip();
                body = new StringBuilder();
                continue;
            }

            if (line == null) {
                if (role != null) {
                    sections.add(section(role, body.toString()));
                }
                if (!sections.isEmpty() || title != null || !frontMatter.isEmpty()) {
                    blocks.add(new Block(frontMatter, title, sections));
                }
                break;
            }

            if (body.length() > 0) {
                body.append('\n');
            }
            body.append(line);
        }

        return blocks;
    }

    private static List<Section> closeSections(List<Section> sections, String role, StringBuilder body) {
        if (role != null) {
            sections.add(section(role, body.toString()));
        }
        return sections;
    }

    private static boolean isH1(String line) {
        return line.startsWith("# ") || line.equals("#");
    }

    /**
     * Le o front-matter no indice {@code from}. Devolve o indice da primeira linha de
     * conteudo. Nao-front-matter deixa o mapa intacto e devolve {@code from}.
     */
    private static int readFrontMatter(String[] lines, int from, Map<String, String> into) {
        var i = from;
        while (i < lines.length && lines[i].isBlank()) {
            i++;
        }
        if (i >= lines.length || !lines[i].strip().equals(FRONT_MATTER_FENCE)) {
            return from;
        }
        i++;
        while (i < lines.length && !lines[i].strip().equals(FRONT_MATTER_FENCE)) {
            var line = lines[i];
            var colon = line.indexOf(':');
            if (colon > 0) {
                into.put(line.substring(0, colon).strip().toLowerCase(java.util.Locale.ROOT),
                        unquote(line.substring(colon + 1).strip()));
            }
            i++;
        }
        return i < lines.length ? i + 1 : lines.length;
    }

    /** Extrai a data opcional {@code <!-- 2026-09-20T14:00:00Z -->} do corpo. */
    private static Section section(String role, String body) {
        var content = body;
        var createdAt = (Instant) null;
        var comment = java.util.regex.Pattern.compile("(?m)^\\s*<!--\\s*(.*?)\\s*-->\\s*$");
        var matcher = comment.matcher(content);
        if (matcher.find()) {
            createdAt = DateTimes.parse(matcher.group(1));
            content = (content.substring(0, matcher.start()) + content.substring(matcher.end()))
                    .strip();
        }
        return new Section(role, content, createdAt);
    }

    private static NormalizedChat toChat(Block block) {
        if (block.messages().isEmpty()) {
            return null; // secao sem mensagem: nao e uma conversa
        }
        var fm = block.frontMatter();
        var title = firstPresent(fm.get("title"), block.title());
        if (title == null) {
            title = firstLine(block.messages().getFirst().body());
        }

        var messages = new ArrayList<NormalizedChat.NormalizedMessage>(block.messages().size());
        for (var section : block.messages()) {
            if (section.body().isBlank()) {
                continue;
            }
            messages.add(new NormalizedChat.NormalizedMessage(null,
                    RoleMapper.from(section.role()), section.body(), section.createdAt()));
        }
        if (messages.isEmpty()) {
            return null;
        }

        var createdAt = DateTimes.parse(fm.get("createdat"));
        var updatedAt = DateTimes.parse(fm.get("updatedat"));
        return new NormalizedChat(
                firstPresent(fm.get("id"), fm.get("externalid")),
                title, createdAt, updatedAt, messages, tags(fm.get("tags")));
    }

    /** Tags no formato {@code [Java, Spring]} ou {@code Java, Spring}. */
    private static Set<String> tags(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        var body = raw.strip();
        if (body.startsWith("[") && body.endsWith("]")) {
            body = body.substring(1, body.length() - 1);
        }
        var out = new LinkedHashSet<String>();
        for (var part : body.split(",")) {
            var tag = unquote(part.strip());
            if (!tag.isEmpty()) {
                out.add(tag);
            }
        }
        return out;
    }

    private static String unquote(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1).strip();
        }
        return value;
    }

    private static String firstPresent(String... values) {
        for (var value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return null;
    }

    private static String firstLine(String value) {
        var stripped = value.strip();
        var newline = stripped.indexOf('\n');
        return newline > 0 ? stripped.substring(0, newline).strip() : stripped;
    }

    private static String stripBom(String text) {
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }
}
