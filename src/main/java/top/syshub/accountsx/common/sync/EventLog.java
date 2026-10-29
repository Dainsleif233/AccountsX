package top.syshub.accountsx.common.sync;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 追加事件日志：把"别的实例改了配置"的发现延迟压到轮询周期。
 *
 * <p>它不是正确性来源——任何一处用令牌之前都必须读盘。日志只是让界面能在 250ms 内
 * 跟着变化，而不是等到用户下次点刷新才发现。因此<b>丢行是安全的</b>：
 * 轮转、被截断、读一半都只会让发现晚一点，不会让数据错。</p>
 */
public final class EventLog {
    private static final Logger LOGGER = LoggerFactory.getLogger(EventLog.class);
    private static final long MAX_BYTES = 256L * 1024L;
    private static final int KEEP_LINES = 200;

    private final SyncPaths paths;

    public EventLog(SyncPaths paths) {
        this.paths = paths;
    }

    /** 一次读取的结果：{@code offset} 是下次继续读的位置，只消费到最后一个完整行。 */
    public record Batch(long offset, List<JsonObject> events) {
    }

    public Batch read(long offset) {
        Path file = paths.eventsLog();
        if (!Files.isRegularFile(file)) {
            return new Batch(0L, List.of());
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            LOGGER.debug("读取事件日志失败: {}", file, e);
            return new Batch(offset, List.of());
        }
        // 日志被轮转/截断：偏移量失效，从头读
        long start = offset > bytes.length ? 0L : offset;
        int lastLineEnd = lastNewline(bytes, (int) start);
        if (lastLineEnd < 0) {
            return new Batch(start, List.of());
        }
        String text = new String(bytes, (int) start, lastLineEnd + 1 - (int) start, StandardCharsets.UTF_8);
        List<JsonObject> events = new ArrayList<>();
        for (String line : text.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            try {
                JsonElement parsed = JsonParser.parseString(line);
                if (parsed instanceof JsonObject jo) {
                    events.add(jo);
                }
            } catch (RuntimeException e) {
                // 半截行/手工编辑：跳过这一行，不因此中断整个轮询
                LOGGER.debug("跳过无法解析的事件行。", e);
            }
        }
        return new Batch(lastLineEnd + 1L, events);
    }

    public void append(JsonObject event) {
        Path file = paths.eventsLog();
        try {
            Path dir = file.getParent();
            if (dir != null) {
                Files.createDirectories(dir);
            }
            Files.writeString(file, event.toString() + "\n", StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND,
                    java.nio.file.StandardOpenOption.WRITE);
            rotateIfNeeded(file);
        } catch (IOException | RuntimeException e) {
            // 事件只是加速发现：写不进去不影响任何数据正确性
            LOGGER.debug("追加事件失败: {}", file, e);
        }
    }

    /** 超过上限时保留末尾若干行重写；截断丢掉的旧事件只意味着"晚一点发现"。 */
    private void rotateIfNeeded(Path file) throws IOException {
        if (Files.size(file) <= MAX_BYTES) {
            return;
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        List<String> keep = lines.size() <= KEEP_LINES ? lines : lines.subList(lines.size() - KEEP_LINES, lines.size());
        SyncFiles.writeAtomically(file, String.join("\n", keep) + "\n");
    }

    private static int lastNewline(byte[] bytes, int from) {
        for (int i = bytes.length - 1; i >= from; i--) {
            if (bytes[i] == '\n') {
                return i;
            }
        }
        return -1;
    }
}
