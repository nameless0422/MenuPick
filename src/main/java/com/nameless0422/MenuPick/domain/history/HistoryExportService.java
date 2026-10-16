package com.nameless0422.MenuPick.domain.history;

import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HistoryExportService {
    public static final int MAX_ROWS = 1000;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final HistoryRepository histories;
    private final Clock clock;

    public ExportFile export(Long userId, Integer days, Boolean visited, String keyword) {
        HistoryFilters filters = HistoryFilters.of(clock, days, keyword);
        var rows = histories.findExportRows(userId, filters.after(), visited, filters.pattern(),
                PageRequest.of(0, MAX_ROWS + 1));
        if (rows.size() > MAX_ROWS) throw new BusinessException(ErrorCode.HISTORY_EXPORT_LIMIT_EXCEEDED);
        StringBuilder csv = new StringBuilder("\uFEFF");
        appendRow(csv, "기록 ID", "추천 시각(KST)", "메뉴", "식당", "방문 여부", "방문 시각(KST)", "추천 피드백", "메모");
        for (var row : rows) {
            appendRow(csv, row.getId().toString(), time(row.getRecommendedAt()), row.getMenuName(),
                    row.getRestaurantName(), row.getVisited() ? "방문" : "미방문", time(row.getVisitedAt()),
                    row.getFeedback() == null ? "" : row.getFeedback() == RecommendationFeedback.ACCEPTED ? "수락" : "거절",
                    row.getMemo());
        }
        return new ExportFile("menupick-history-" + LocalDate.now(clock) + ".csv",
                csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String time(LocalDateTime value) { return value == null ? "" : TIME.format(value); }

    private static void appendRow(StringBuilder csv, String... values) {
        csv.append(Arrays.stream(values).map(HistoryExportService::cell).collect(Collectors.joining(",")))
                .append("\r\n");
    }

    /** 구분자·따옴표·줄바꿈은 CSV 인용으로 보존하고 수식 시작 문자는 텍스트로 보정한다. */
    static String cell(String value) {
        String text = value == null ? "" : value;
        int start = 0;
        while (start < text.length() && (Character.isWhitespace(text.charAt(start))
                || Character.isSpaceChar(text.charAt(start)) || Character.isISOControl(text.charAt(start))
                || text.charAt(start) == '\uFEFF')) start++;
        if ((start < text.length() && "=+-@＝＋－＠".indexOf(text.charAt(start)) >= 0)
                || (!text.isEmpty() && "\t\r\n".indexOf(text.charAt(0)) >= 0)) {
            text = "\t" + text;
        }
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    public record ExportFile(String filename, byte[] content) {}
}
