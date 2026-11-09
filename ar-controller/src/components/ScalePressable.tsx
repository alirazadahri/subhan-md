import React, { useRef } from 'react';
import { Animated, Pressable, type PressableProps, type StyleProp, type ViewStyle } from 'react-native';

type Props = Omit<PressableProps, 'style'> & { style?: StyleProp<ViewStyle>; scaleTo?: number };

/** Pressable that springs down slightly while touched (runs on the native driver). */
export function ScalePressable({ style, scaleTo = 0.96, children, onPressIn, onPressOut, ...rest }: Props) {
  const scale = useRef(new Animated.Value(1)).current;
  const spring = (to: number) =>
    Animated.spring(scale, { toValue: to, useNativeDriver: true, speed: 40, bounciness: 6 }).start();

  return (
    <Pressable
      {...rest}
      onPressIn={e => {
        spring(scaleTo);
        onPressIn?.(e);
      }}
      onPressOut={e => {
        spring(1);
        onPressOut?.(e);
      }}>
      {state => (
        <Animated.View style={[style, { transform: [{ scale }] }, rest.disabled && { opacity: 0.45 }]}>
          {typeof children === 'function' ? children(state) : children}
        </Animated.View>
      )}
    </Pressable>
  );
}
