import React from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { colors, gradients } from '../theme';
import { ScalePressable } from './ScalePressable';

type Props = {
  icon: string;
  tint: string;
  title: string;
  description: string;
  granted: boolean;
  actionLabel?: string;
  onPress: () => void;
};

export function PermissionRow({ icon, tint, title, description, granted, actionLabel, onPress }: Props) {
  return (
    <ScalePressable style={[styles.row, !granted && styles.rowPending]} onPress={granted ? undefined : onPress} scaleTo={0.98}>
      <View style={[styles.iconBox, { backgroundColor: tint + '26' }]}>
        <Text style={styles.icon}>{icon}</Text>
      </View>
      <View style={styles.body}>
        <Text style={styles.title}>{title}</Text>
        <Text style={styles.desc} numberOfLines={2}>
          {description}
        </Text>
      </View>
      {granted ? (
        <View style={styles.onChip}>
          <Text style={styles.onText}>✓ On</Text>
        </View>
      ) : (
        <View style={styles.button}>
          <Text style={styles.buttonText}>{actionLabel ?? 'Allow'}</Text>
        </View>
      )}
    </ScalePressable>
  );
}

const styles = StyleSheet.create({
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    padding: 12,
    borderRadius: 18,
    backgroundColor: colors.cardAlt,
    marginTop: 10,
    borderWidth: 1,
    borderColor: 'transparent',
  },
  rowPending: { borderColor: '#7C3AED66' },
  iconBox: { width: 44, height: 44, borderRadius: 14, alignItems: 'center', justifyContent: 'center' },
  icon: { fontSize: 22 },
  body: { flex: 1, paddingHorizontal: 12 },
  title: { color: colors.text, fontSize: 15, fontWeight: '700' },
  desc: { color: colors.muted, fontSize: 12, marginTop: 2, lineHeight: 16 },
  onChip: { backgroundColor: '#10B98122', borderRadius: 12, paddingHorizontal: 10, paddingVertical: 6 },
  onText: { color: colors.ok, fontWeight: '800', fontSize: 12 },
  button: {
    experimental_backgroundImage: gradients.primary,
    borderRadius: 12,
    paddingHorizontal: 14,
    paddingVertical: 8,
  },
  buttonText: { color: '#fff', fontWeight: '800', fontSize: 13 },
});
