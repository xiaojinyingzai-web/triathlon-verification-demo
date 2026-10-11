package com.triathlon.prep;

import com.triathlon.prep.core.Verification;
import com.triathlon.prep.ui.VerificationView;

/** Maps core snapshots to presentation; never generates verdicts or photos. */
final class VerificationPresenter {
    static VerificationView.Model model(Verification.Snapshot state, Verification.Mode mode, String problem) {
        return model(state,mode,problem,mode==Verification.Mode.TEST);
    }
    static VerificationView.Model model(Verification.Snapshot state, Verification.Mode mode, String problem,boolean canBegin) {
        boolean simulation = mode == Verification.Mode.TEST;
        int step = 1;
        String title = "正在初始化", message = "正在检查本地私有日志。", instruction = "请稍候";
        String name = null, number = null, score = null, liveness = null, saved = null;
        VerificationView.PrimaryAction action = VerificationView.PrimaryAction.NONE;
        VerificationView.Tone tone = VerificationView.Tone.INFO;
        boolean enabled = false, cancel = false;
        if (state != null) {
            name = state.name; number = state.maskedNumber;
            switch (state.stage) {
                case IDLE:
                    title = simulation ? "等待虚构流程测试" : canBegin?"准备配置已载入，等待受控核验":"设备未接入，核验不可用";
                    message = simulation ? "点击开始运行虚构流程；结果不构成身份核验。"
                            : canBegin?"按已授权联调范围开始；配置已载入不等于实机能力已验证。":"完整 LIVE 未启用；请先核实设备、授权与评分配置。";
                    instruction = simulation ? "点击开始虚构测试" : canBegin?"按受控联调流程开始":"请先核实准备配置";
                    action = canBegin ? VerificationView.PrimaryAction.BEGIN : VerificationView.PrimaryAction.NONE;
                    enabled = canBegin; tone = simulation ? VerificationView.Tone.INFO : VerificationView.Tone.WARNING;
                    break;
                case READING:
                    title = simulation ? "模拟读证中" : "正在读取身份证";
                    message = "仅使用本轮芯片证件照片，不使用历史入库人脸。";
                    instruction = "请将身份证放在读卡区"; cancel = true; break;
                case CAPTURING:
                    step = 2; title = simulation ? "模拟采集/活体中" : "正在采集现场人脸";
                    message = "等待本轮采集与活体结果。"; instruction = "请正对摄像头"; cancel = true; break;
                case ANALYZING:
                    step = 3; title = simulation ? "模拟比对中" : "正在进行人证比对";
                    message = "本次芯片证件照与本次现场采集做1:1比对。";
                    instruction = "正在处理，请稍候"; cancel = true; break;
                case FINISHED:
                    step = 4;
                    Verification.Record result = state.result;
                    if (result != null) {
                        switch (result.verdict) {
                            case PASSED: title = result.eventVerdict==Verification.Verdict.PASSED
                                    ?simulation?"模拟赛事核验通过 · 非身份核验":"赛事核验通过"
                                    :"历史人证结果 · 未核验赛事名单";
                                tone = result.eventVerdict==Verification.Verdict.PASSED?VerificationView.Tone.GOOD:VerificationView.Tone.WARNING; break;
                            case REJECTED: title = simulation ? "模拟流程未通过" : "核验未通过";
                                tone = VerificationView.Tone.BAD; break;
                            case CANCELLED: title = "本轮已取消"; name = null; number = null;
                                tone = VerificationView.Tone.WARNING; break;
                            default: title = "核验不可用"; tone = VerificationView.Tone.WARNING; break;
                        }
                        message = reason(result.error);
                        if (simulation) message = "虚构测试：" + message + " 设备与算法尚未接入。";
                        if (result.score != null && result.threshold != null) {
                            score = (simulation ? "模拟 " : "") + result.score + "（阈值 " + result.threshold
                                    + (result.rule == Verification.ScoreRule.AT_MOST ? "，≤）" : "，≥）");
                        }
                        liveness = (simulation ? "模拟 " : "") + (result.liveness == Verification.Liveness.PASSED
                                ? "通过" : result.liveness == Verification.Liveness.FAILED ? "未通过" : "不可用");
                    }
                    instruction = "本轮已结束，请查看结果";
                    if (state.saveState == Verification.SaveState.FAILED) {
                        saved = "保存失败"; title = "记录保存失败";
                        message = "本轮结果尚未保存，请重试；暂不能进入下一位。";
                        action = VerificationView.PrimaryAction.RETRY_SAVE; tone = VerificationView.Tone.WARNING;
                    } else {
                        saved = state.saveState == Verification.SaveState.SAVED ? "已保存" : "未保存";
                        action = VerificationView.PrimaryAction.NEXT;
                    }
                    enabled = true; break;
                default: break;
            }
        }
        if (problem != null) { message = problem; tone = VerificationView.Tone.WARNING; }
        return new VerificationView.Model(simulation, step, title, message, instruction, name, number,
                score, liveness, saved, null, null, action, enabled, cancel, tone,roster(state));
    }
    private static VerificationView.RosterInfo roster(Verification.Snapshot state){
        if(state==null)return null;
        boolean current=state.stage!=Verification.Stage.IDLE&&(state.result==null||state.result.verdict!=Verification.Verdict.CANCELLED);
        boolean matched=current&&state.membership==Verification.Membership.MATCHED&&state.athlete!=null;
        String notice=!current?"等待本次名单核对":matched?"本场名单已匹配":state.membership==Verification.Membership.NOT_LISTED?"本场名单未匹配":"本场名单尚未匹配";
        return new VerificationView.RosterInfo(current?state.eventName:"",current&&!state.rosterBatchId.isEmpty()?"已锁定本场名单":"",notice,matched,
            matched?state.athlete.name:null,matched?state.athlete.bib:null,matched?state.athlete.group:null,matched?state.athlete.team:null);
    }
    private static String reason(Verification.Error error) {
        switch (error) {
            case NONE: return "本轮流程完成，结果已由核验核心生成。";
            case NOT_CONNECTED: return "设备或算法未接入，无法进行核验。";
            case NO_ROSTER: return "尚未导入本场名单，不能进行赛事核验。";
            case NOT_LISTED: return "本次身份证号不在本场有效名单中，未启动现场采集或比对。";
            case ROSTER_ERROR: return "本场名单读取失败，不能进行赛事核验；请检查或重新导入。";
            case WRONG_MODE: return "适配器模式不一致，已停止核验。";
            case INVALID_CARD: return "证件信息无效，请重新读证。";
            case MISSING_CHIP_PHOTO: return "未读取芯片证件照片，不能降级为直接刷脸。";
            case NO_FACE: return "未检测到人脸。";
            case MULTIPLE_FACES: return "检测到多张人脸，请单人核验。";
            case BAD_CAPTURE: return "现场采集质量不合格。";
            case LIVENESS_FAILED: return "活体检测未通过。";
            case NO_MATCH: return "人证比对未达到算法阈值。";
            case INVALID_COMPARISON: return "算法结果不完整或无效。";
            case LICENSE_ERROR: return "算法许可不可用。";
            case PERMISSION_DENIED: return "摄像头权限未授予，无法采集；请按受控联调步骤核实权限。";
            case TIMEOUT: return "本轮处理超时。";
            case CANCELLED: return "本轮已取消，照片和本轮展示信息已清空。";
            default: return "设备或算法调用失败，核验不可用。";
        }
    }
}
