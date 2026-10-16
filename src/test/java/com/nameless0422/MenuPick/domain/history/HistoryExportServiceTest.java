package com.nameless0422.MenuPick.domain.history;

import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.Pageable;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.Collections;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class HistoryExportServiceTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-16T03:00:00Z"), ZoneId.of("Asia/Seoul"));
    private final HistoryRepository histories = mock(HistoryRepository.class);
    private final HistoryExportService service = new HistoryExportService(histories, clock);

    @Test void exportsQuotedUnicodeMultilineAndNullFields() {
        HistoryRepository.ExportRow row = mock(HistoryRepository.ExportRow.class);
        when(row.getId()).thenReturn(7L);
        when(row.getRecommendedAt()).thenReturn(LocalDateTime.of(2026, 10, 16, 12, 0));
        when(row.getMenuName()).thenReturn("국수, \"곱빼기\"");
        when(row.getMemo()).thenReturn("첫 줄\r\n둘째 줄");
        when(histories.findExportRows(anyLong(), any(), any(), any(), any())).thenReturn(List.of(row));
        var file = service.export(1L, 30, false, "  100%_!  ");
        assertThat(file.filename()).isEqualTo("menupick-history-2026-10-16.csv");
        String csv = new String(file.content(), StandardCharsets.UTF_8);
        assertThat(csv).startsWith("\uFEFF\"기록 ID\"").endsWith(
                "\"7\",\"2026-10-16 12:00:00\",\"국수, \"\"곱빼기\"\"\",\"\",\"미방문\",\"\",\"\",\"첫 줄\r\n둘째 줄\"\r\n");
        verify(histories).findExportRows(eq(1L), eq(LocalDateTime.of(2026, 9, 16, 12, 0)),
                eq(false), eq("%100!%!_!!%"), argThat(p -> p.getPageSize() == 1001));
    }

    @ParameterizedTest
    @ValueSource(strings = {"=1+2", "+1", "-1", "@SUM(A1)", "  =1", "\t=1", "\r=1", "\n=1", "＝1", "＋1", "－1", "＠SUM(A1)", "\u00a0=1", "\uFEFF=1", "\u0000=1"})
    void neutralizesFormulaPrefixesInsideQuotedCells(String input) {
        assertThat(HistoryExportService.cell(input)).isEqualTo("\"\t" + input + "\"");
    }

    @Test void emptyExportStillContainsHeaderAndDefaultPeriod() {
        when(histories.findExportRows(anyLong(), any(), any(), any(), any())).thenReturn(List.of());
        assertThat(new String(service.export(1L, null, null, " ").content(), StandardCharsets.UTF_8))
                .isEqualTo("\uFEFF\"기록 ID\",\"추천 시각(KST)\",\"메뉴\",\"식당\",\"방문 여부\",\"방문 시각(KST)\",\"추천 피드백\",\"메모\"\r\n");
        verify(histories).findExportRows(eq(1L), eq(LocalDateTime.of(2026, 10, 9, 12, 0)),
                isNull(), isNull(), any(Pageable.class));
    }

    @Test void refusesOverLimitInsteadOfReturningPartialFile() {
        when(histories.findExportRows(anyLong(), any(), any(), any(), any()))
                .thenReturn(Collections.nCopies(1001, mock(HistoryRepository.ExportRow.class)));
        assertThatThrownBy(() -> service.export(1L, 7, null, null)).isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.HISTORY_EXPORT_LIMIT_EXCEEDED);
    }
}
