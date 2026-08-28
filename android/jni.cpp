// JNI bridge for the Android host. Exposes the Chuks engine (the same //export'd
// chuks_* functions the iOS host calls) and a compact, handle-based Yoga API to
// Kotlin. Yoga node refs are passed as jlong. This is the Android analogue of
// the Swift host calling Yoga's C API directly.

#include <jni.h>
#include <yoga/Yoga.h>
#include "_cgo_export.h"   // chuks_setup / chuks_mount / chuks_drain / ... (Go exports)

#define J(name) Java_com_chuks_app_N_##name

extern "C" {

// ---- engine ----
JNIEXPORT void  JNICALL J(setup)(JNIEnv* e, jobject, jint n) { chuks_set_count(n); }
JNIEXPORT jint  JNICALL J(mount)(JNIEnv* e, jobject) { return chuks_mount(); }
JNIEXPORT jint  JNICALL J(tick)(JNIEnv* e, jobject) { return chuks_tick(); }
JNIEXPORT jint  JNICALL J(viewport)(JNIEnv* e, jobject, jint t, jint h, jint w) { return chuks_setViewport(t, h, w); }
JNIEXPORT jstring JNICALL J(drain)(JNIEnv* e, jobject) {
    char* s = chuks_drain();
    jstring r = e->NewStringUTF(s);
    chuks_free_str(s);
    return r;
}
JNIEXPORT jint JNICALL J(event)(JNIEnv* e, jobject, jstring a) {
    const char* c = e->GetStringUTFChars(a, 0);
    int m = chuks_dispatch((char*)c);
    e->ReleaseStringUTFChars(a, c);
    return m;
}
JNIEXPORT jint JNICALL J(input)(JNIEnv* e, jobject, jstring a, jstring v) {
    const char* ca = e->GetStringUTFChars(a, 0);
    const char* cv = e->GetStringUTFChars(v, 0);
    int m = chuks_dispatchInput((char*)ca, (char*)cv);
    e->ReleaseStringUTFChars(a, ca);
    e->ReleaseStringUTFChars(v, cv);
    return m;
}
// Async host->engine bridge (F3): report a native capability result for `token`.
JNIEXPORT jint JNICALL J(resolve)(JNIEnv* e, jobject, jstring tok, jstring payload) {
    const char* ct = e->GetStringUTFChars(tok, 0);
    const char* cp = e->GetStringUTFChars(payload, 0);
    int m = chuks_resolve((char*)ct, (char*)cp);
    e->ReleaseStringUTFChars(tok, ct);
    e->ReleaseStringUTFChars(payload, cp);
    return m;
}
// F3 error channel: report a capability FAILURE for `token`.
JNIEXPORT jint JNICALL J(fail)(JNIEnv* e, jobject, jstring tok, jstring msg) {
    const char* ct = e->GetStringUTFChars(tok, 0);
    const char* cm = e->GetStringUTFChars(msg, 0);
    int m = chuks_fail((char*)ct, (char*)cm);
    e->ReleaseStringUTFChars(tok, ct);
    e->ReleaseStringUTFChars(msg, cm);
    return m;
}
JNIEXPORT void JNICALL J(setColorScheme)(JNIEnv*, jobject, jint dark) { chuks_setColorScheme(dark); }
JNIEXPORT jint JNICALL J(colorSchemeFollows)(JNIEnv*, jobject) { return chuks_colorSchemeFollows(); }
JNIEXPORT void JNICALL J(setInsets)(JNIEnv*, jobject, jint t, jint r, jint b, jint l) { chuks_setInsets(t, r, b, l); }
JNIEXPORT void JNICALL J(setPlatform)(JNIEnv* e, jobject, jstring os, jstring ver, jstring model, jint isTablet) {
    const char* co = e->GetStringUTFChars(os, 0);
    const char* cv = e->GetStringUTFChars(ver, 0);
    const char* cm = e->GetStringUTFChars(model, 0);
    chuks_setPlatform((char*)co, (char*)cv, (char*)cm, isTablet);
    e->ReleaseStringUTFChars(os, co);
    e->ReleaseStringUTFChars(ver, cv);
    e->ReleaseStringUTFChars(model, cm);
}

// ---- host wake ----
// A background Chuks task calls host.wake() when it posts work to the render
// thread; the engine invokes this trampoline (on the task's own thread). Attach
// to the JVM and call the Kotlin static N.onNativeWake(), which hops to the UI
// thread and ticks. Registered once from Kotlin via setWake().
static JavaVM*   gWakeJVM   = nullptr;
static jclass    gWakeClass = nullptr;   // global ref to com/chuks/app/N
static jmethodID gWakeMid   = nullptr;   // onNativeWake()V (static)

static void chuks_wake_trampoline() {
    if (!gWakeJVM || !gWakeClass || !gWakeMid) return;
    JNIEnv* env = nullptr;
    bool attached = false;
    if (gWakeJVM->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        if (gWakeJVM->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        attached = true;
    }
    env->CallStaticVoidMethod(gWakeClass, gWakeMid);
    if (attached) gWakeJVM->DetachCurrentThread();
}

JNIEXPORT void JNICALL J(setWake)(JNIEnv* e, jobject) {
    e->GetJavaVM(&gWakeJVM);
    jclass cls = e->FindClass("com/chuks/app/N");
    if (cls) {
        gWakeClass = (jclass)e->NewGlobalRef(cls);
        gWakeMid = e->GetStaticMethodID(gWakeClass, "onNativeWake", "()V");
    }
    chuks_set_wake(reinterpret_cast<void*>(chuks_wake_trampoline));
}

// CMR: load a chukspack source bundle into the in-process VM. Only built when
// linking libcmr (-DCMR_BUILD); the AOT libapp has no chuks_cmr_boot.
#ifdef CMR_BUILD
extern "C" int chuks_cmr_boot(char* bundle, int length);
extern "C" void chuks_cmr_set_tmpdir(char* path);
JNIEXPORT jint JNICALL J(cmrBoot)(JNIEnv* e, jobject, jbyteArray b, jstring tmp) {
    // The bundle compiles on-device via os.MkdirTemp. An Android app sandbox has
    // no writable /tmp and no $TMPDIR, so hand the VM the app's cache dir (into
    // Go directly: Go snapshots the environ at startup, so a C setenv is unseen).
    const char* ct = e->GetStringUTFChars(tmp, nullptr);
    if (ct) { chuks_cmr_set_tmpdir((char*)ct); e->ReleaseStringUTFChars(tmp, ct); }
    jsize n = e->GetArrayLength(b);
    jbyte* p = e->GetByteArrayElements(b, nullptr);
    jint rc = chuks_cmr_boot((char*)p, (int)n);
    e->ReleaseByteArrayElements(b, p, JNI_ABORT);
    return rc;
}
// Hot-reload delta: merge only the changed modules into the kept set + re-init.
extern "C" int chuks_cmr_apply_delta(char* delta, int length);
JNIEXPORT jint JNICALL J(cmrApplyDelta)(JNIEnv* e, jobject, jbyteArray d) {
    jsize n = e->GetArrayLength(d);
    jbyte* p = e->GetByteArrayElements(d, nullptr);
    jint rc = chuks_cmr_apply_delta((char*)p, (int)n);
    e->ReleaseByteArrayElements(d, p, JNI_ABORT);
    return rc;
}
// Hot-reload state preservation: save before a reboot, restore into the fresh VM.
extern "C" char* chuks_cmr_save_state();
extern "C" void chuks_cmr_load_state(char* state);
JNIEXPORT jstring JNICALL J(cmrSaveState)(JNIEnv* e, jobject) {
    char* s = chuks_cmr_save_state();
    jstring r = e->NewStringUTF(s ? s : "");
    if (s) chuks_free_str(s);
    return r;
}
JNIEXPORT void JNICALL J(cmrLoadState)(JNIEnv* e, jobject, jstring st) {
    const char* c = e->GetStringUTFChars(st, nullptr);
    if (c) { chuks_cmr_load_state((char*)c); e->ReleaseStringUTFChars(st, c); }
}
#endif

// ---- Yoga (handle-based) ----
// Shared config: opt into Yoga's classic "errata" layout so a measured Text re-wraps to
// its resolved width (matches the iOS host).
static YGConfigRef gYogaConfig = nullptr;
static YGConfigRef yogaConfig() {
    if (!gYogaConfig) { gYogaConfig = YGConfigNew(); YGConfigSetErrata(gYogaConfig, YGErrataAll); }
    return gYogaConfig;
}
// Text measure callback: Yoga calls this for a text leaf during layout with the resolved
// width constraint; we hop into Kotlin (N.measureTextNode) to measure the real TextView so
// Text wraps to its container width without an explicit width, like the iOS host does.
static JNIEnv*   gLayoutEnv    = nullptr;
static jclass    gMeasureClass = nullptr;
static jmethodID gMeasureMid   = nullptr;
static YGSize androidTextMeasure(YGNodeConstRef node, float width, YGMeasureMode wmode, float, YGMeasureMode) {
    if (!gLayoutEnv || !gMeasureClass || !gMeasureMid) return YGSize{0, 0};
    jlong packed = gLayoutEnv->CallStaticLongMethod(gMeasureClass, gMeasureMid, (jlong)node, (jfloat)width, (jint)wmode);
    return YGSize{(float)(int)(packed >> 32), (float)(int)(packed & 0xffffffff)};
}
JNIEXPORT jlong JNICALL J(yNew)(JNIEnv*, jobject) { return (jlong)YGNodeNewWithConfig(yogaConfig()); }
JNIEXPORT void JNICALL J(ySetTextMeasure)(JNIEnv* e, jobject, jlong n) {
    if (!gMeasureClass) {
        jclass cls = e->FindClass("com/chuks/app/N");
        if (cls) { gMeasureClass = (jclass)e->NewGlobalRef(cls);
                   gMeasureMid = e->GetStaticMethodID(gMeasureClass, "measureTextNode", "(JFI)J"); }
    }
    YGNodeSetMeasureFunc((YGNodeRef)n, androidTextMeasure);
}
JNIEXPORT void JNICALL J(yMarkDirty)(JNIEnv*, jobject, jlong n) {
    if (YGNodeHasMeasureFunc((YGNodeRef)n)) YGNodeMarkDirty((YGNodeRef)n);
}
JNIEXPORT void  JNICALL J(yInsert)(JNIEnv*, jobject, jlong p, jlong c, jint i) {
    // A reused node id may have changed kind (Text -> container); Yoga aborts on adding a
    // child to a node that still has a text measure func, so clear it first.
    if (YGNodeHasMeasureFunc((YGNodeRef)p)) YGNodeSetMeasureFunc((YGNodeRef)p, nullptr);
    YGNodeInsertChild((YGNodeRef)p, (YGNodeRef)c, (size_t)i);
}
JNIEXPORT void  JNICALL J(yRemove)(JNIEnv*, jobject, jlong p, jlong c) { YGNodeRemoveChild((YGNodeRef)p, (YGNodeRef)c); }
JNIEXPORT jlong JNICALL J(yOwner)(JNIEnv*, jobject, jlong n) { return (jlong)YGNodeGetOwner((YGNodeRef)n); }
JNIEXPORT jint  JNICALL J(yChildCount)(JNIEnv*, jobject, jlong n) { return (jint)YGNodeGetChildCount((YGNodeRef)n); }
JNIEXPORT void  JNICALL J(yFree)(JNIEnv*, jobject, jlong n) { YGNodeFreeRecursive((YGNodeRef)n); }
JNIEXPORT void  JNICALL J(yCalc)(JNIEnv* e, jobject, jlong n, jfloat w, jfloat h) {
    gLayoutEnv = e;
    YGNodeCalculateLayout((YGNodeRef)n, w, h, YGDirectionLTR);
    gLayoutEnv = nullptr;
}
JNIEXPORT jfloat JNICALL J(yGet)(JNIEnv*, jobject, jlong n, jint which) {
    YGNodeRef y = (YGNodeRef)n;
    switch (which) {
        case 0: return YGNodeLayoutGetLeft(y);
        case 1: return YGNodeLayoutGetTop(y);
        case 2: return YGNodeLayoutGetWidth(y);
        default: return YGNodeLayoutGetHeight(y);
    }
}
// key: 0 flexDir(v:0col/1row) 1 justify 2 align 3 grow 4 basis 5 w 6 h
//      7 padAll 8 gapAll 9 posAbs 10 posTop 11 posLeft 12 posRight
//      13 flexWrap 14 padHorizontal 15 padVertical
//      16-19 pad T/R/B/L  20-23 margin T/R/B/L  24-27 min/max W/H
//      28 widthPct 29 heightPct 30 aspect(v/100) 31 posBottom
JNIEXPORT void JNICALL J(ySetF)(JNIEnv*, jobject, jlong n, jint key, jfloat v) {
    YGNodeRef y = (YGNodeRef)n; int iv = (int)v;
    switch (key) {
        case 0:  YGNodeStyleSetFlexDirection(y, iv == 1 ? YGFlexDirectionRow : iv == 2 ? YGFlexDirectionRowReverse : iv == 3 ? YGFlexDirectionColumnReverse : YGFlexDirectionColumn); break;
        case 1:  YGNodeStyleSetJustifyContent(y, (YGJustify)iv); break;
        case 2:  YGNodeStyleSetAlignItems(y, (YGAlign)iv); break;
        case 3:  YGNodeStyleSetFlexGrow(y, v); break;
        case 4:  YGNodeStyleSetFlexBasis(y, v); break;
        case 5:  YGNodeStyleSetWidth(y, v); break;
        case 6:  YGNodeStyleSetHeight(y, v); break;
        case 7:  YGNodeStyleSetPadding(y, YGEdgeAll, v); break;
        case 8:  YGNodeStyleSetGap(y, YGGutterAll, v); break;
        case 9:  YGNodeStyleSetPositionType(y, YGPositionTypeAbsolute); break;
        case 10: YGNodeStyleSetPosition(y, YGEdgeTop, v); break;
        case 11: YGNodeStyleSetPosition(y, YGEdgeLeft, v); break;
        case 12: YGNodeStyleSetPosition(y, YGEdgeRight, v); break;
        case 13: YGNodeStyleSetFlexWrap(y, iv == 1 ? YGWrapWrap : YGWrapNoWrap); break;
        case 14: YGNodeStyleSetPadding(y, YGEdgeHorizontal, v); break;   // px
        case 15: YGNodeStyleSetPadding(y, YGEdgeVertical, v); break;     // py
        case 16: YGNodeStyleSetPadding(y, YGEdgeTop, v); break;
        case 17: YGNodeStyleSetPadding(y, YGEdgeRight, v); break;
        case 18: YGNodeStyleSetPadding(y, YGEdgeBottom, v); break;
        case 19: YGNodeStyleSetPadding(y, YGEdgeLeft, v); break;
        case 20: YGNodeStyleSetMargin(y, YGEdgeTop, v); break;
        case 21: YGNodeStyleSetMargin(y, YGEdgeRight, v); break;
        case 22: YGNodeStyleSetMargin(y, YGEdgeBottom, v); break;
        case 23: YGNodeStyleSetMargin(y, YGEdgeLeft, v); break;
        case 24: YGNodeStyleSetMinWidth(y, v); break;
        case 25: YGNodeStyleSetMaxWidth(y, v); break;
        case 26: YGNodeStyleSetMinHeight(y, v); break;
        case 27: YGNodeStyleSetMaxHeight(y, v); break;
        case 28: YGNodeStyleSetWidthPercent(y, v); break;
        case 29: YGNodeStyleSetHeightPercent(y, v); break;
        case 30: YGNodeStyleSetAspectRatio(y, v / 100.0f); break;
        case 31: YGNodeStyleSetPosition(y, YGEdgeBottom, v); break;
        case 32: YGNodeStyleSetDisplay(y, iv == 1 ? YGDisplayNone : YGDisplayFlex); break;   // hidden (display:none)
        case 33: YGNodeStyleSetAlignSelf(y, (YGAlign)iv); break;                             // align-self
        case 34: YGNodeStyleSetOverflow(y, YGOverflowScroll); break;                         // scroll container: content overflows
    }
}

} // extern "C"
