import React, { useCallback, useEffect, useState } from 'react';
import {
  ActivityIndicator,
  Alert,
  AppState,
  LayoutAnimation,
  Linking,
  ScrollView,
  StatusBar,
  StyleSheet,
  Switch,
  Text,
  TextInput,
  View,
} from 'react-native';
import { SafeAreaProvider, SafeAreaView } from 'react-native-safe-area-context';
import { FadeIn } from './src/components/FadeIn';
import { PermissionRow } from './src/components/PermissionRow';
import { ScalePressable } from './src/components/ScalePressable';
import {
  ARController,
  checkRuntime,
  requestAllRuntime,
  requestRuntime,
  type AIConfig,
  type NativeStatus,
  type RuntimeKey,
} from './src/native/ARController';
import { colors, gradients } from './src/theme';

type Status = NativeStatus & Record<RuntimeKey, boolean>;

const GROQ_URL = 'https://api.groq.com/openai/v1';
const GROQ_MODEL = 'llama-3.3-70b-versatile';

const EXAMPLES = [
  'YouTube float karo',
  'WhatsApp floating',
  'torch on',
  'Ali ko call karo',
  'WhatsApp pe Ali ko hello bhejo',
  'YouTube pe Atif Aslam ka gaana chalao',
  'wifi off karo',
];

export default function App() {
  return (
    <SafeAreaProvider>
      <StatusBar barStyle="light-content" />
      <SafeAreaView style={styles.safe}>
        <Home />
      </SafeAreaView>
    </SafeAreaProvider>
  );
}

function Home() {
  const [status, setStatus] = useState<Status | null>(null);
  const [config, setConfig] = useState<AIConfig | null>(null);
  const [apiKey, setApiKey] = useState('');
  const [testText, setTestText] = useState('');
  const [testReply, setTestReply] = useState('');
  const [busy, setBusy] = useState(false);

  const refresh = useCallback(async () => {
    try {
      const [nativeStatus, runtime] = await Promise.all([ARController.getStatus(), checkRuntime()]);
      LayoutAnimation.configureNext(LayoutAnimation.Presets.easeInEaseOut);
      setStatus({ ...nativeStatus, ...runtime });
    } catch (e: any) {
      Alert.alert('AR Controller', e?.message ?? String(e));
    }
  }, []);

  useEffect(() => {
    refresh();
    ARController.getConfig().then(setConfig).catch(() => {});
    // Re-check when the user comes back from a Settings screen.
    const sub = AppState.addEventListener('change', s => s === 'active' && refresh());
    return () => sub.remove();
  }, [refresh]);

  if (!status || !config) {
    return <ActivityIndicator style={styles.loader} color={colors.primary} size="large" />;
  }

  const required = [status.overlay, status.microphone, status.accessibility, status.contacts, status.phone, status.notifications];
  const done = required.filter(Boolean).length;
  const running = status.serviceRunning;

  const toggleController = async () => {
    try {
      if (running) ARController.stopController();
      else await ARController.startController();
    } catch (e: any) {
      Alert.alert('AR Controller', e?.message ?? String(e));
    }
    setTimeout(refresh, 400);
  };

  const openFreeformSetup = async () => {
    const opened = await ARController.openDeveloperOptions();
    if (opened === 'about') {
      Alert.alert(
        'Developer options band hain',
        '“Build number” (ya “Version”) par 7 baar tap karein. Phir wapas aa kar dobara ye button dabayein.',
      );
    }
  };

  const enableFreeformNow = async () => {
    const ok = await ARController.enableFreeform();
    Alert.alert(
      ok ? 'Freeform on ✓' : 'Nahi ho saka',
      ok ? 'Ab phone restart karein — phir har app floating box mein khulegi.' : 'Developer options se manually on karein.',
    );
    refresh();
  };

  const saveConfig = async () => {
    await ARController.saveConfig({
      apiKey: apiKey || undefined,
      baseUrl: config.baseUrl,
      model: config.model,
      language: config.language,
      wakeWordEnabled: config.wakeWordEnabled,
      freeAiEnabled: config.freeAiEnabled,
    });
    setApiKey('');
    setConfig(await ARController.getConfig());
    Alert.alert('Saved ✓', 'AI settings save ho gayi.');
  };

  const runTest = async (text = testText) => {
    if (!text.trim()) return;
    setBusy(true);
    try {
      const r = await ARController.runCommand(text.trim());
      LayoutAnimation.configureNext(LayoutAnimation.Presets.easeInEaseOut);
      setTestReply(`${r.reply}\n→ ${r.action}${r.target ? ` · ${r.target}` : ''}`);
    } catch (e: any) {
      setTestReply(`Error: ${e?.message ?? e}`);
    } finally {
      setBusy(false);
    }
  };

  const ask = (key: RuntimeKey) => requestRuntime(key).then(refresh);

  return (
    <ScrollView contentContainerStyle={styles.scroll} keyboardShouldPersistTaps="handled" showsVerticalScrollIndicator={false}>
      {/* Hero */}
      <FadeIn style={styles.hero}>
        <View style={styles.heroTop}>
          <View style={styles.logo}>
            <Text style={styles.logoText}>AR</Text>
          </View>
          <View style={[styles.pill, running ? styles.pillOn : styles.pillOff]}>
            <View style={[styles.dot, { backgroundColor: running ? '#34D399' : '#CBD5E1' }]} />
            <Text style={styles.pillText}>{running ? 'Active' : 'Off'}</Text>
          </View>
        </View>
        <Text style={styles.h1}>AR Controller</Text>
        <Text style={styles.heroSub}>Bolo “Hey AR” — apps kholo, call karo, phone control karo.</Text>

        <View style={styles.progressRow}>
          <Text style={styles.progressText}>Setup {done}/{required.length}</Text>
          <Text style={styles.progressText}>{Math.round((done / required.length) * 100)}%</Text>
        </View>
        <View style={styles.progressTrack}>
          <View style={[styles.progressFill, { width: `${(done / required.length) * 100}%` }]} />
        </View>

        <ScalePressable
          style={[styles.heroButton, running && styles.heroButtonStop]}
          onPress={toggleController}
          disabled={!status.overlay}>
          <Text style={[styles.heroButtonText, running && styles.heroButtonTextStop]}>
            {running ? '■  Stop floating AR' : '▶  Start floating AR'}
          </Text>
        </ScalePressable>
        {!status.overlay ? <Text style={styles.heroHint}>Pehle “Display over other apps” allow karein</Text> : null}
      </FadeIn>

      {/* Permissions */}
      {status.lastError ? (
        <FadeIn style={styles.errorCard}>
          <Text style={styles.errorTitle}>⚠ Pichli baar error aaya tha</Text>
          <Text style={styles.errorText} numberOfLines={6} selectable>
            {status.lastError}
          </Text>
          <View style={styles.errorButtons}>
            <ScalePressable
              style={styles.errorButton}
              onPress={() => {
                ARController.copyText(status.lastError ?? '');
                Alert.alert('Copied ✓', 'Ye error chat mein bhej dein.');
              }}>
              <Text style={styles.errorButtonText}>Copy error</Text>
            </ScalePressable>
            <ScalePressable
              style={styles.errorButton}
              onPress={() => {
                ARController.clearLastError();
                refresh();
              }}>
              <Text style={styles.errorButtonText}>Dismiss</Text>
            </ScalePressable>
          </View>
        </FadeIn>
      ) : null}
      <FadeIn delay={80} style={styles.card}>
        <View style={styles.cardHeader}>
          <Text style={styles.h2}>Permissions</Text>
          {done < required.length ? (
            <ScalePressable style={styles.chip} onPress={() => requestAllRuntime().then(refresh)}>
              <Text style={styles.chipText}>Allow all</Text>
            </ScalePressable>
          ) : (
            <Text style={styles.allSet}>All set ✓</Text>
          )}
        </View>
        <PermissionRow
          icon="🪟"
          tint="#8B5CF6"
          title="Display over other apps"
          description="Floating icon aur control panel"
          granted={status.overlay}
          actionLabel="Open"
          onPress={() => ARController.openOverlaySettings()}
        />
        <PermissionRow
          icon="🎙"
          tint="#EC4899"
          title="Microphone"
          description="“Hey AR” voice commands"
          granted={status.microphone}
          onPress={() => ask('microphone')}
        />
        <PermissionRow
          icon="♿"
          tint="#3B82F6"
          title="Accessibility"
          description="Settings → Installed/Downloaded apps → AR Controller → On"
          granted={status.accessibility}
          actionLabel="Open"
          onPress={() => ARController.openAccessibilitySettings()}
        />
        <PermissionRow
          icon="👥"
          tint="#10B981"
          title="Contacts"
          description="Naam se call: “Ali ko call karo”"
          granted={status.contacts}
          onPress={() => ask('contacts')}
        />
        <PermissionRow
          icon="📞"
          tint="#F59E0B"
          title="Phone calls"
          description="Seedha call lagana"
          granted={status.phone}
          onPress={() => ask('phone')}
        />
        <PermissionRow
          icon="🔔"
          tint="#F97316"
          title="Notifications"
          description="Background mein chalta rahe"
          granted={status.notifications}
          onPress={() => ask('notifications')}
        />
        {!status.accessibility && status.sdk >= 33 ? (
          <ScalePressable onPress={() => ARController.openAppDetails()}>
            <Text style={styles.hint}>
              “Restricted setting” aa raha hai? Yahan tap → ⋮ menu → “Allow restricted settings”, phir Accessibility on
              karein.
            </Text>
          </ScalePressable>
        ) : null}
      </FadeIn>

      {/* Floating window setup */}
      <FadeIn delay={160} style={styles.card}>
        <View style={styles.cardHeader}>
          <Text style={styles.h2}>Floating app window</Text>
          <Text style={status.freeform ? styles.allSet : styles.optional}>
            {status.freeform ? 'All apps ✓' : 'Web box mode'}
          </Text>
        </View>
        <View style={styles.modeBox}>
          <Text style={styles.modeTitle}>✓ Abhi bhi floating chalega</Text>
          <Text style={styles.modeText}>
            YouTube, WhatsApp, Facebook, Instagram, TikTok, Gmail, Maps, Telegram, X, Chrome… bina kisi setup ke floating
            box mein khulti hain. Box ko title bar se drag karein, ◢ kone se resize, – se chhota, ✕ se band.
          </Text>
        </View>
        <Text style={styles.small}>Har app (asli app) ko floating box mein kholne ke liye ek baar ye setup:</Text>
        <Step n={1} done={status.devOptionsEnabled} text="Settings → About phone → “Build number” par 7 baar tap" />
        <Step n={2} done={status.freeform} text="Developer options → “Enable freeform windows” + “Force activities to be resizable” on" />
        <Step n={3} done={status.freeform} text="Phone restart karein" />
        {!status.freeform && status.canWriteSecureSettings ? (
          <ScalePressable style={styles.gradientButton} onPress={enableFreeformNow}>
            <Text style={styles.gradientButtonText}>⚡ One-tap enable (phir restart)</Text>
          </ScalePressable>
        ) : null}
        {!status.freeform ? (
          <ScalePressable style={styles.outlineButton} onPress={openFreeformSetup}>
            <Text style={styles.outlineButtonText}>
              {status.devOptionsEnabled ? 'Open Developer options' : 'Open About phone'}
            </Text>
          </ScalePressable>
        ) : null}
      </FadeIn>

      {/* AI */}
      <FadeIn delay={240} style={styles.card}>
        <Text style={styles.h2}>AI brain</Text>
        <View style={styles.switchRow}>
          <View style={styles.flex}>
            <Text style={styles.switchLabel}>Free AI (bina key)</Text>
            <Text style={styles.switchSub}>
              Key ke baghair AI chalegi (pollinations.ai). Apni key daalein to wo use hogi — zyada tez aur private.
            </Text>
          </View>
          <Switch
            value={config.freeAiEnabled}
            onValueChange={freeAiEnabled => setConfig({ ...config, freeAiEnabled })}
            trackColor={{ true: colors.primary, false: colors.border }}
            thumbColor="#fff"
          />
        </View>
        <Text style={styles.small}>Apni key (optional): OpenAI, Groq, OpenRouter ya koi bhi OpenAI-compatible API.</Text>
        <View style={styles.tipBox}>
          <Text style={styles.tipText}>
            Free AI mein limit hai (har 45 sec mein 1 jawab), is liye “WhatsApp pe msg bhejo” jaise lambe kaam slow honge.
            Tez aur free: Groq key (1 minute) — neeche button dabayein, key copy karke yahan paste karein, Save.
          </Text>
          <ScalePressable
            style={styles.groqButton}
            onPress={() => {
              setConfig({ ...config, baseUrl: GROQ_URL, model: GROQ_MODEL });
              Linking.openURL('https://console.groq.com/keys');
            }}>
            <Text style={styles.groqButtonText}>⚡ Free Groq key lein + setup</Text>
          </ScalePressable>
        </View>
        <Text style={styles.label}>API key {config.hasApiKey ? '· saved ✓' : ''}</Text>
        <TextInput
          style={styles.input}
          value={apiKey}
          onChangeText={setApiKey}
          placeholder={config.hasApiKey ? '•••••••• (khaali chhorein to purani rahegi)' : 'sk-…'}
          placeholderTextColor={colors.muted}
          secureTextEntry
          autoCapitalize="none"
        />
        <Text style={styles.label}>Base URL</Text>
        <TextInput
          style={styles.input}
          value={config.baseUrl}
          onChangeText={baseUrl => setConfig({ ...config, baseUrl })}
          autoCapitalize="none"
        />
        <View style={styles.rowInputs}>
          <View style={styles.modelCol}>
            <Text style={styles.label}>Model</Text>
            <TextInput
              style={styles.input}
              value={config.model}
              onChangeText={model => setConfig({ ...config, model })}
              autoCapitalize="none"
            />
          </View>
          <View style={styles.langCol}>
            <Text style={styles.label}>Voice language</Text>
            <TextInput
              style={styles.input}
              value={config.language}
              onChangeText={language => setConfig({ ...config, language })}
              autoCapitalize="none"
            />
          </View>
        </View>
        <View style={styles.switchRow}>
          <View style={styles.flex}>
            <Text style={styles.switchLabel}>Always listen for “Hey AR”</Text>
            <Text style={styles.switchSub}>Off karein to sirf mic button se sunega</Text>
          </View>
          <Switch
            value={config.wakeWordEnabled}
            onValueChange={wakeWordEnabled => setConfig({ ...config, wakeWordEnabled })}
            trackColor={{ true: colors.primary, false: colors.border }}
            thumbColor="#fff"
          />
        </View>
        <ScalePressable style={styles.gradientButton} onPress={saveConfig}>
          <Text style={styles.gradientButtonText}>Save AI settings</Text>
        </ScalePressable>
      </FadeIn>

      {/* Try it */}
      <FadeIn delay={320} style={styles.card}>
        <Text style={styles.h2}>Try a command</Text>
        <View style={styles.chips}>
          {EXAMPLES.map(e => (
            <ScalePressable
              key={e}
              style={styles.example}
              onPress={() => {
                setTestText(e);
                runTest(e);
              }}>
              <Text style={styles.exampleText}>{e}</Text>
            </ScalePressable>
          ))}
        </View>
        <View style={styles.sendRow}>
          <TextInput
            style={[styles.input, styles.flex]}
            value={testText}
            onChangeText={setTestText}
            placeholder="Kuch bhi likhein…"
            placeholderTextColor={colors.muted}
            onSubmitEditing={() => runTest()}
            returnKeyType="send"
          />
          <ScalePressable style={styles.sendButton} onPress={() => runTest()} disabled={busy}>
            {busy ? <ActivityIndicator color="#fff" /> : <Text style={styles.sendText}>➤</Text>}
          </ScalePressable>
        </View>
        {testReply ? (
          <View style={styles.replyBox}>
            <Text style={styles.replyText}>{testReply}</Text>
          </View>
        ) : null}
      </FadeIn>

      <Text style={styles.footer}>Tip: floating “AR” icon par tap → ALL APPS → kisi app par tap = floating window</Text>
    </ScrollView>
  );
}

function Step({ n, text, done }: { n: number; text: string; done: boolean }) {
  return (
    <View style={styles.step}>
      <View style={[styles.stepNum, done && styles.stepNumDone]}>
        <Text style={styles.stepNumText}>{done ? '✓' : n}</Text>
      </View>
      <Text style={[styles.stepText, done && styles.stepTextDone]}>{text}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: colors.bg },
  flex: { flex: 1 },
  loader: { flex: 1 },
  scroll: { padding: 16, paddingBottom: 56 },

  hero: {
    borderRadius: 28,
    padding: 20,
    experimental_backgroundImage: gradients.hero,
    boxShadow: '0px 12px 32px rgba(124, 58, 237, 0.45)',
  },
  heroTop: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  logo: {
    width: 56,
    height: 56,
    borderRadius: 28,
    backgroundColor: 'rgba(255,255,255,0.18)',
    borderWidth: 2,
    borderColor: 'rgba(255,255,255,0.7)',
    alignItems: 'center',
    justifyContent: 'center',
  },
  logoText: { color: '#fff', fontSize: 20, fontWeight: '900' },
  pill: { flexDirection: 'row', alignItems: 'center', borderRadius: 20, paddingHorizontal: 12, paddingVertical: 6 },
  pillOn: { backgroundColor: 'rgba(16,185,129,0.25)' },
  pillOff: { backgroundColor: 'rgba(255,255,255,0.15)' },
  dot: { width: 8, height: 8, borderRadius: 4, marginRight: 6 },
  pillText: { color: '#fff', fontWeight: '700', fontSize: 12 },
  h1: { color: '#fff', fontSize: 28, fontWeight: '900', marginTop: 14 },
  heroSub: { color: 'rgba(255,255,255,0.85)', fontSize: 14, marginTop: 4, lineHeight: 20 },
  progressRow: { flexDirection: 'row', justifyContent: 'space-between', marginTop: 18 },
  progressText: { color: 'rgba(255,255,255,0.9)', fontSize: 12, fontWeight: '700' },
  progressTrack: { height: 8, borderRadius: 4, backgroundColor: 'rgba(255,255,255,0.2)', marginTop: 6, overflow: 'hidden' },
  progressFill: { height: 8, borderRadius: 4, backgroundColor: '#fff' },
  heroButton: { marginTop: 18, backgroundColor: '#fff', borderRadius: 16, paddingVertical: 15, alignItems: 'center' },
  heroButtonStop: { backgroundColor: 'rgba(0,0,0,0.25)', borderWidth: 1, borderColor: 'rgba(255,255,255,0.5)' },
  heroButtonText: { color: '#4C1D95', fontWeight: '900', fontSize: 16 },
  heroButtonTextStop: { color: '#fff' },
  heroHint: { color: '#FDE68A', fontSize: 12, textAlign: 'center', marginTop: 8 },

  errorCard: { marginTop: 16, borderRadius: 20, padding: 14, backgroundColor: '#EF44441A', borderWidth: 1, borderColor: '#EF444466' },
  errorTitle: { color: '#FCA5A5', fontWeight: '800', fontSize: 14 },
  errorText: { color: colors.text, fontSize: 11, marginTop: 6, fontFamily: 'monospace' },
  errorButtons: { flexDirection: 'row', marginTop: 10 },
  errorButton: { marginRight: 10, borderRadius: 12, paddingHorizontal: 14, paddingVertical: 8, backgroundColor: '#EF444433' },
  errorButtonText: { color: '#fff', fontWeight: '800', fontSize: 12 },

  card: {
    backgroundColor: colors.card,
    borderRadius: 24,
    padding: 16,
    marginTop: 16,
    borderWidth: 1,
    borderColor: colors.border,
  },
  cardHeader: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  h2: { color: colors.text, fontSize: 18, fontWeight: '800' },
  chip: { backgroundColor: '#7C3AED33', borderRadius: 14, paddingHorizontal: 12, paddingVertical: 6 },
  chipText: { color: '#C4B5FD', fontWeight: '800', fontSize: 12 },
  allSet: { color: colors.ok, fontWeight: '800', fontSize: 13 },
  optional: { color: colors.muted, fontWeight: '700', fontSize: 12 },
  small: { color: colors.muted, fontSize: 13, lineHeight: 19, marginTop: 6 },
  hint: { color: colors.warn, fontSize: 12, marginTop: 12, lineHeight: 18 },

  step: { flexDirection: 'row', alignItems: 'center', marginTop: 12 },
  stepNum: {
    width: 26,
    height: 26,
    borderRadius: 13,
    backgroundColor: colors.cardAlt,
    borderWidth: 1,
    borderColor: colors.border,
    alignItems: 'center',
    justifyContent: 'center',
  },
  stepNumDone: { backgroundColor: colors.ok, borderColor: colors.ok },
  stepNumText: { color: '#fff', fontWeight: '800', fontSize: 12 },
  stepText: { flex: 1, color: colors.text, fontSize: 13, marginLeft: 10, lineHeight: 18 },
  stepTextDone: { color: colors.muted, textDecorationLine: 'line-through' },
  outlineButton: {
    marginTop: 14,
    borderRadius: 14,
    borderWidth: 1.5,
    borderColor: colors.primary,
    paddingVertical: 12,
    alignItems: 'center',
  },
  outlineButtonText: { color: '#C4B5FD', fontWeight: '800', fontSize: 14 },
  modeBox: { marginTop: 10, borderRadius: 14, padding: 12, backgroundColor: '#10B9811A', borderWidth: 1, borderColor: '#10B98144' },
  modeTitle: { color: colors.ok, fontWeight: '800', fontSize: 13 },
  modeText: { color: colors.text, fontSize: 12, lineHeight: 18, marginTop: 4 },
  tipBox: { marginTop: 10, borderRadius: 14, padding: 12, backgroundColor: '#F59E0B14', borderWidth: 1, borderColor: '#F59E0B44' },
  tipText: { color: colors.text, fontSize: 12, lineHeight: 18 },
  groqButton: { marginTop: 10, borderRadius: 12, paddingVertical: 10, alignItems: 'center', backgroundColor: '#F59E0B' },
  groqButtonText: { color: '#1F1300', fontWeight: '900', fontSize: 13 },

  label: { color: colors.muted, fontSize: 12, marginTop: 12, marginBottom: 6, fontWeight: '600' },
  input: {
    backgroundColor: colors.bg,
    color: colors.text,
    borderRadius: 14,
    borderWidth: 1,
    borderColor: colors.border,
    paddingHorizontal: 14,
    paddingVertical: 11,
    fontSize: 14,
  },
  rowInputs: { flexDirection: 'row' },
  modelCol: { flex: 1, marginRight: 10 },
  langCol: { width: 120 },
  switchRow: { flexDirection: 'row', alignItems: 'center', marginTop: 16 },
  switchLabel: { color: colors.text, fontSize: 14, fontWeight: '600' },
  switchSub: { color: colors.muted, fontSize: 12, marginTop: 2 },
  gradientButton: {
    marginTop: 16,
    borderRadius: 16,
    paddingVertical: 14,
    alignItems: 'center',
    experimental_backgroundImage: gradients.primary,
  },
  gradientButtonText: { color: '#fff', fontWeight: '900', fontSize: 15 },

  chips: { flexDirection: 'row', flexWrap: 'wrap', marginTop: 10, marginHorizontal: -4 },
  example: {
    backgroundColor: colors.cardAlt,
    borderRadius: 14,
    paddingHorizontal: 12,
    paddingVertical: 8,
    margin: 4,
    borderWidth: 1,
    borderColor: colors.border,
  },
  exampleText: { color: '#C4B5FD', fontSize: 12, fontWeight: '600' },
  sendRow: { flexDirection: 'row', alignItems: 'center', marginTop: 12 },
  sendButton: {
    width: 48,
    height: 48,
    borderRadius: 24,
    marginLeft: 10,
    alignItems: 'center',
    justifyContent: 'center',
    experimental_backgroundImage: gradients.primary,
  },
  sendText: { color: '#fff', fontSize: 18, fontWeight: '900' },
  replyBox: { marginTop: 12, borderRadius: 14, padding: 12, backgroundColor: colors.cardAlt },
  replyText: { color: colors.text, fontSize: 13, lineHeight: 19 },

  footer: { color: colors.muted, fontSize: 12, textAlign: 'center', marginTop: 20, lineHeight: 18 },
});
