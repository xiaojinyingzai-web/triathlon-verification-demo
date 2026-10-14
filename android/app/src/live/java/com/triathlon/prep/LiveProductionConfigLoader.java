package com.triathlon.prep;

import android.content.Context;import com.csht.enums.ReadCardType;
import com.triathlon.prep.core.*;import com.triathlon.prep.cefk.face.FaceClient;import com.triathlon.prep.cefk.hardware.CshtReaderFactory;
import java.io.File;import java.util.*;

/** Cold-start preparation only. Never initializes SDK, requests permissions, contacts service, or prints. */
final class LiveProductionConfigLoader {
    enum Problem {
        PUBLIC_MISSING("公开参数待填"),PUBLIC_INVALID("公开参数不合法"),PUBLIC_IO("公开参数读取失败"),
        PRIVATE_MISSING("私有凭据与启动政策待配置"),PRIVATE_KEY("私有保护密钥不可用"),PRIVATE_INVALID("私有设置损坏或认证失败"),PRIVATE_IO("私有设置读取失败"),
        PUBLIC_CHANGED("公开参数已变更，需重新确认私有启动政策"),READER_POLICY("厂商读卡凭据政策待确认"),READER_CREDENTIALS("读卡凭据不完整或不符合厂商政策"),FACE_CREDENTIALS("人脸凭据待填"),
        EXECUTION("执行授权待确认"),OFFLINE_READER("离线读卡行为待厂商确认"),USB_NETWORK_POLICY("USB联网读证政策待独立确认；完整业务启动仍禁用"),DEPENDENCIES("本机SDK依赖待确认"),IMAGE_CONTRACT("图像契约待确认"),THRESHOLDS("阈值待确认"),FACE_SERVICE("服务使用授权待确认"),MAPPING_FAILURE("生产参数准备失败");
        final String text;Problem(String text){this.text=text;}
    }
    static final class Result {
        final LiveRuntime.Config config;final List<Problem> problems;
        private Result(LiveRuntime.Config config,List<Problem> problems){this.config=config;this.problems=Collections.unmodifiableList(new ArrayList<>(problems));}
        boolean ready(){return config!=null&&problems.isEmpty();}
        VerificationRuntime attach(VerificationRuntime runtime){runtime.setStartupNotice(runtimeNotice(runtime.canBegin()));return runtime;}
        String notice(){if(ready())return "生产启动输入已准备；实体设备、联网与出纸尚未验证。";StringBuilder out=new StringBuilder("生产启动未就绪：");for(Problem p:problems){if(out.length()>9)out.append("；");out.append(p.text);}return out.toString();}
        String runtimeNotice(boolean canBegin){return ready()?(canBegin?notice():"生产装配失败，核验已禁用；请检查本机依赖，凭据不回显。设备、联网与出纸尚未验证。"):notice();}
        @Override public String toString(){return "ProductionPreparation["+problems+"]";}
    }
    private LiveProductionConfigLoader(){}
    static Result load(Context app){
        if(app==null)return failure(Problem.PUBLIC_IO);
        try{return load(new FileDeviceConfigStore(new File(app.getFilesDir(),"device-config")),AndroidLaunchStore.open(app));}
        catch(Exception error){return failure(Problem.PUBLIC_IO);}
    }
    static Result load(FileDeviceConfigStore drafts,EncryptedLaunchStore privateStore){
        DeviceConfigDraft draft;
        try{draft=drafts.load().current;}catch(Exception error){return failure(Problem.PUBLIC_IO);}
        // An incomplete public draft never even reads private keys.
        if(draft==null||!draft.review().validForSave||!draft.review().parametersComplete)return map(draft,null);
        EncryptedLaunchStore.Load privateLoad=privateStore.load();
        switch(privateLoad.status){
            case READY:return map(draft,privateLoad.settings);
            case MISSING:return failure(Problem.PRIVATE_MISSING);
            case KEY_UNAVAILABLE:return failure(Problem.PRIVATE_KEY);
            case IO_FAILURE:return failure(Problem.PRIVATE_IO);
            default:return failure(Problem.PRIVATE_INVALID);
        }
    }
    static Result map(DeviceConfigDraft draft,PrivateLaunchSettings settings){
        List<Problem> problems=new ArrayList<>();
        if(draft==null||!draft.review().parametersComplete){problems.add(draft!=null&&!draft.review().validForSave?Problem.PUBLIC_INVALID:Problem.PUBLIC_MISSING);return new Result(null,problems);}
        if(settings==null)return failure(Problem.PRIVATE_MISSING);
        if(!settings.matches(draft))return failure(Problem.PUBLIC_CHANGED);
        if(settings.readerPolicy==PrivateLaunchSettings.ReaderCredentialsPolicy.UNCONFIRMED)problems.add(Problem.READER_POLICY);
        else if(settings.readerPolicy==PrivateLaunchSettings.ReaderCredentialsPolicy.REQUIRED
                ?!present(settings.readerId())||!present(settings.readerKey())||!present(settings.readerSecret())
                :!settings.readerId().isEmpty()||!settings.readerKey().isEmpty()||!settings.readerSecret().isEmpty())problems.add(Problem.READER_CREDENTIALS);
        if(!present(settings.faceId())||!present(settings.faceKey())||!present(settings.faceSecret()))problems.add(Problem.FACE_CREDENTIALS);
        for(PrivateLaunchSettings.Confirm confirm:PrivateLaunchSettings.requiredConfirmations(draft))if(!settings.confirmed(confirm))problems.add(confirm.name().startsWith("USB_")?Problem.USB_NETWORK_POLICY:Problem.valueOf(confirm.name()));
        if(!problems.isEmpty())return new Result(null,problems);
        try{
            LiveRuntime.Config base=LiveConfigDraftLoader.map(draft);if(base==null)return failure(Problem.MAPPING_FAILURE);
            boolean serial="SAM_SERIALPORT".equals(draft.value(DeviceConfigDraft.Field.READER_TRANSPORT).trim());
            CshtReaderFactory.Configuration reader=new CshtReaderFactory.Configuration(serial?ReadCardType.SAM_SERIALPORT:"USB".equals(draft.value(DeviceConfigDraft.Field.READER_TRANSPORT))?ReadCardType.USB:ReadCardType.SAM_USB,
                    serial?draft.value(DeviceConfigDraft.Field.SERIAL_PATH).trim():null,serial?Integer.parseInt(draft.value(DeviceConfigDraft.Field.SERIAL_BAUD).trim()):0,
                    settings.readerId(),settings.readerKey(),settings.readerSecret(),settings.samCode(),"USB".equals(draft.value(DeviceConfigDraft.Field.READER_TRANSPORT).trim())?"yfs4.sfzydq.com":null,"USB".equals(draft.value(DeviceConfigDraft.Field.READER_TRANSPORT).trim())?9999:0);
            FaceClient.Config f=base.face;FaceClient.Config face=new FaceClient.Config(f.mode,f.endpoint,f.deviceNumber,f.deviceName,f.byteFormat,f.signCase,
                    settings.confirmed(PrivateLaunchSettings.Confirm.IMAGE_CONTRACT),settings.confirmed(PrivateLaunchSettings.Confirm.THRESHOLDS),settings.confirmed(PrivateLaunchSettings.Confirm.FACE_SERVICE),f.timeoutMs,f.maxImageBytes,f.tokenTtlMs);
            CefkFaceAdapter.Parameters p=base.decision;CefkFaceAdapter.Parameters decision=draft.scoresOnly()?CefkFaceAdapter.Parameters.scoresOnly():new CefkFaceAdapter.Parameters(p.liveThreshold,p.liveRule,p.faceThreshold,p.faceRule,settings.confirmed(PrivateLaunchSettings.Confirm.THRESHOLDS));
            FaceClient.CredentialsProvider credentials=()->new FaceClient.Credentials(settings.faceId(),settings.faceKey(),settings.faceSecret());
            return new Result(new LiveRuntime.Config(reader,null,face,credentials,decision,base.printer,base.readerTimeoutMillis,base.stageTimeoutMillis,base.printTimeoutMillis,
                    settings.confirmed(PrivateLaunchSettings.Confirm.EXECUTION),settings.confirmed(PrivateLaunchSettings.Confirm.OFFLINE_READER),settings.confirmed(PrivateLaunchSettings.Confirm.DEPENDENCIES),
                    base.standardCamera,base.previewStableMillis,base.previewFreshMillis,base.externalPhoto,
                    settings.confirmed(PrivateLaunchSettings.Confirm.USB_NETWORK)&&settings.confirmed(PrivateLaunchSettings.Confirm.USB_INITIALIZATION)&&settings.confirmed(PrivateLaunchSettings.Confirm.USB_MANAGED_FALLBACK)),problems);
        }catch(IllegalArgumentException|LinkageError error){return failure(Problem.MAPPING_FAILURE);}
    }
    private static boolean present(String value){return value!=null&&!value.trim().isEmpty();}
    private static Result failure(Problem problem){return new Result(null,Collections.singletonList(problem));}
}
