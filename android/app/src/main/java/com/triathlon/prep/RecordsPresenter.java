package com.triathlon.prep;

import com.triathlon.prep.core.Verification;
import com.triathlon.prep.ui.RecordsView;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Read-only display mapping. No fixture, storage writes, identity enrichment or scoring. */
final class RecordsPresenter {
    static boolean canOpen(Verification.Snapshot state) {
        return state != null && state.saveState != Verification.SaveState.FAILED
                && (state.stage == Verification.Stage.IDLE || state.stage == Verification.Stage.FINISHED);
    }
    static RecordsView.Model model(List<Verification.Record> input, Verification.Mode mode,
            RecordsView.Filter filter, String selected, boolean loading, String error) {
        boolean simulation = mode == Verification.Mode.TEST;
        List<RecordsView.Row> rows = new ArrayList<>();
        String notice = loading ? "正在读取本机私有日志。" : error != null ? "本机私有日志读取失败。"
                : (simulation ? "已保存的虚构流程日志" : "已保存的本机核验日志") + " · 时间为北京时间";
        if (!loading && error == null) {
            List<Verification.Record> sorted = new ArrayList<>(input);
            sorted.sort((a, b) -> {
                int time = Long.compare(b.timestamp, a.timestamp);
                return time == 0 ? a.sessionId.compareTo(b.sessionId) : time;
            });
            SimpleDateFormat date = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA);
            date.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
            for (Verification.Record record : sorted) {
                if (record.mode != mode) {
                    return new RecordsView.Model(simulation, false, filter, Collections.emptyList(), null,
                            "本机私有日志", "日志模式与当前应用不一致，记录未展示。请检查日志来源。");
                }
                RecordsView.Result result = record.eventVerdict == Verification.Verdict.PASSED ? RecordsView.Result.PASSED
                        : record.eventVerdict == Verification.Verdict.REJECTED ? RecordsView.Result.REJECTED : RecordsView.Result.UNAVAILABLE;
                if (filter != RecordsView.Filter.ALL && !filter.name().equals(result.name())) continue;
                boolean test = record.mode == Verification.Mode.TEST;
                String score = record.score == null || record.threshold == null ? "—"
                        : (test ? "模拟 " : "") + record.score + " / " + record.threshold
                                + (record.rule == Verification.ScoreRule.AT_MOST ? "（≤）" : "（≥）");
                String liveness = record.liveness == Verification.Liveness.PASSED ? "通过"
                        : record.liveness == Verification.Liveness.FAILED ? "未通过" : "不可用";
                String source = "CURRENT_CHIP_TO_CURRENT_CAPTURE".equals(record.comparisonSource)
                        ? "本轮芯片证件照 → 本轮现场采集"
                        : "TEST_FIXTURE_PAIR".equals(record.comparisonSource) ? "虚构证件 / 采集字节" : "无本轮完整照片对";
                rows.add(new RecordsView.Row(record.sessionId, date.format(record.timestamp), record.maskedNumber,
                        result, (test ? "虚构测试：" : "") + (record.eventVerdict==null?"历史人证日志，未做赛事名单核验；原人证结论："+record.verdict.name():reason(record.error))+(!record.rosterBatchId.isEmpty()?" · 名单批次 "+record.rosterBatchId:""), score,
                        (test ? "模拟 " : "") + liveness, source, "已保存 · 日志读回",
                        record.algorithmVersion, record.error.name(), test));
            }
        }
        return new RecordsView.Model(simulation, loading, filter, rows, selected, notice, error);
    }
    private static String reason(Verification.Error error) {
        switch (error) {
            case NONE: return "本轮流程完成，结论来自该会话已保存结果。";
            case CANCELLED: return "本轮已取消，未形成核验结论。";
            case NOT_CONNECTED: return "设备或算法未接入。";
            case NO_ROSTER:return "尚未导入本场名单。";
            case NOT_LISTED:return "证号不在本场名单，未进行人证比对。";
            case ROSTER_ERROR:return "名单读取失败，赛事核验不可用。";
            case WRONG_MODE: return "适配器模式不一致。";
            case INVALID_CARD: return "证件信息无效。";
            case MISSING_CHIP_PHOTO: return "未读取芯片证件照片，不能降级为直接刷脸。";
            case NO_FACE: return "未检测到人脸。";
            case MULTIPLE_FACES: return "检测到多张人脸。";
            case BAD_CAPTURE: return "现场采集质量不合格。";
            case LIVENESS_FAILED: return "活体检测未通过。";
            case NO_MATCH: return "人证比对未达到算法阈值。";
            case INVALID_COMPARISON: return "算法结果无效。";
            case LICENSE_ERROR: return "算法许可不可用。";
            case PERMISSION_DENIED: return "摄像头权限未授予，无法采集。";
            case TIMEOUT: return "本轮处理超时。";
            default: return "设备或算法调用失败，核验不可用。";
        }
    }
}
