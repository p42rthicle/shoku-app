import React from 'react';
import {
  AbsoluteFill,
  Img,
  Sequence,
  continueRender,
  delayRender,
  interpolate,
  spring,
  staticFile,
  useCurrentFrame,
  useVideoConfig,
} from 'remotion';
// The app's own typefaces (SIL Open Font License), straight from the Android project.
import interUrl from '../../android/app/src/main/res/font/inter_variable.ttf';
import groteskUrl from '../../android/app/src/main/res/font/space_grotesk_variable.ttf';

/*
 * Shoku's showcase: 1080 x 1920, about 29 s. Every phone screen is a PNG rendered by the app's screenshot
 * tests (docs/screenshots, served as Remotion's public folder). Monochrome like the app, and every move
 * is a spring, like the app's Material 3 Expressive motion.
 */

export const FPS = 30;

// The app's palette (ui/theme/Color.kt, light).
const INK = '#09090B';
const MUTED = '#6B6B74';
const TINT = '#F4F4F5';
const LINE = '#E4E4E7';
const PAPER = '#FFFFFF';

const fontsReady = delayRender('Loading fonts');
Promise.all([
  new FontFace('Inter', `url(${interUrl}) format('truetype')`, {weight: '100 900'}).load(),
  new FontFace('Space Grotesk', `url(${groteskUrl}) format('truetype')`, {weight: '300 700'}).load(),
]).then((faces) => {
  faces.forEach((f) => document.fonts.add(f));
  continueRender(fontsReady);
});

type Scene = {title: string; line: string; screens: string[]};

const SCENES: Scene[] = [
  {title: 'Log in seconds', line: 'Your usual meals are one tap away.', screens: ['01-today.png', '03-add.png']},
  {title: 'Describe it, or photograph it', line: 'AI turns words or a plate photo into entries.', screens: ['04-describe.png']},
  {title: 'Scan Indian packs', line: 'Barcodes through Open Food Facts.', screens: ['05-scan.png']},
  {title: 'Batches and stock', line: 'Cook once, eat all week. Know what is at home.', screens: ['11-batch.png', '09-library-stock.png']},
  {title: 'Plan your week', line: 'Meal templates by weekday, and supplements.', screens: ['12-plan-meals.png', '13-plan-supplements.png']},
  {title: 'Search everything', line: 'Log, library, stock and plan in one box.', screens: ['07-search-everything.png']},
  {title: 'Bring your own Claude key', line: 'Calls go from your phone to Anthropic, under a monthly cap.', screens: ['15-settings-ai.png']},
];

const INTRO = 66;
const STEP = 100; // a new scene every 100 frames
const SCENE_LEN = 116; // 16 frames of overlap for the cross-fade
const OUTRO = 104;
const OUTRO_START = INTRO - 6 + STEP * SCENES.length;
export const TOTAL_FRAMES = OUTRO_START + OUTRO;

// Springs (scripts/fallback_render.py uses the same numbers). CALM is just past critical damping, so it
// settles in about half a second without overshoot; SOFT gives the phone a touch of give (about 1.5 %).
const CALM = {damping: 26, stiffness: 140, mass: 1};
const SOFT = {damping: 16, stiffness: 100, mass: 1};

const fill: React.CSSProperties = {fontFamily: 'Inter, sans-serif', color: INK};

export const Showcase: React.FC = () => (
  <AbsoluteFill style={{backgroundColor: TINT, ...fill}}>
    <Sequence durationInFrames={INTRO + 10} name="Intro">
      <Intro />
    </Sequence>
    {SCENES.map((s, i) => (
      <Sequence key={s.title} from={INTRO - 6 + i * STEP} durationInFrames={SCENE_LEN} name={s.title}>
        <SceneView scene={s} index={i} />
      </Sequence>
    ))}
    <Sequence from={OUTRO_START} durationInFrames={OUTRO} name="Outro">
      <Outro />
    </Sequence>
  </AbsoluteFill>
);

/** Fade in at the start and out at the end of a sequence of [length] frames. */
const useEdges = (length: number, fadeIn = 14, fadeOut = 16) => {
  const frame = useCurrentFrame();
  return interpolate(frame, [0, fadeIn, length - fadeOut, length], [0, 1, 1, 0], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });
};

const Wordmark: React.FC<{size: number}> = ({size}) => (
  <div style={{fontFamily: '"Space Grotesk", sans-serif', fontWeight: 600, fontSize: size, letterSpacing: -size * 0.04, lineHeight: 1}}>
    Shoku
  </div>
);

const Intro: React.FC = () => {
  const frame = useCurrentFrame();
  const {fps} = useVideoConfig();
  const rise = spring({frame, fps, config: SOFT});
  const sub = spring({frame: frame - 10, fps, config: CALM});
  const opacity = useEdges(INTRO + 10, 8, 18);
  return (
    <AbsoluteFill style={{alignItems: 'center', justifyContent: 'center', opacity}}>
      <div style={{transform: `translateY(${(1 - rise) * 60}px) scale(${0.94 + rise * 0.06})`}}>
        <Wordmark size={168} />
      </div>
      <div style={{marginTop: 34, fontSize: 44, color: MUTED, opacity: sub, transform: `translateY(${(1 - sub) * 24}px)`}}>
        A calm food log for Android
      </div>
    </AbsoluteFill>
  );
};

const PHONE_W = 610;
const BEZEL = 16;
const SCREEN_W = PHONE_W - 2 * BEZEL;
const SCREEN_H = Math.round((SCREEN_W * 2400) / 1080);
const PHONE_H = SCREEN_H + 2 * BEZEL;

/** A plain monochrome phone around one or more screens; screens cross-fade with a small lift. */
const Phone: React.FC<{screens: string[]; switchAt?: number}> = ({screens, switchAt = 56}) => {
  const frame = useCurrentFrame();
  const {fps} = useVideoConfig();
  return (
    <div
      style={{
        width: PHONE_W,
        height: PHONE_H,
        borderRadius: 86,
        backgroundColor: INK,
        padding: BEZEL,
        boxSizing: 'border-box',
        boxShadow: '0 50px 90px rgba(9, 9, 11, 0.18), 0 12px 24px rgba(9, 9, 11, 0.10)',
      }}
    >
      <div style={{position: 'relative', width: SCREEN_W, height: SCREEN_H, borderRadius: 70, overflow: 'hidden', backgroundColor: PAPER}}>
        {screens.map((src, i) => {
          // Screen i takes over at i * switchAt.
          const t = i === 0 ? 1 : spring({frame: frame - i * switchAt, fps, config: CALM});
          const next = i + 1 < screens.length ? spring({frame: frame - (i + 1) * switchAt, fps, config: CALM}) : 0;
          return (
            <Img
              key={src}
              src={staticFile(src)}
              style={{
                position: 'absolute',
                inset: 0,
                width: SCREEN_W,
                height: SCREEN_H,
                opacity: i === 0 ? 1 : t,
                transform: `translateY(${(1 - t) * 40}px) scale(${1 - next * 0.02})`,
              }}
            />
          );
        })}
        {/* The camera hole. */}
        <div style={{position: 'absolute', top: 14, left: SCREEN_W / 2 - 11, width: 22, height: 22, borderRadius: 11, backgroundColor: INK}} />
      </div>
    </div>
  );
};

const Dots: React.FC<{active: number}> = ({active}) => (
  <div style={{display: 'flex', gap: 14, justifyContent: 'center'}}>
    {SCENES.map((s, i) => (
      <div
        key={s.title}
        style={{width: i === active ? 40 : 12, height: 12, borderRadius: 6, backgroundColor: i === active ? INK : LINE}}
      />
    ))}
  </div>
);

const SceneView: React.FC<{scene: Scene; index: number}> = ({scene, index}) => {
  const frame = useCurrentFrame();
  const {fps} = useVideoConfig();
  const opacity = useEdges(SCENE_LEN);
  const title = spring({frame, fps, config: CALM});
  const line = spring({frame: frame - 6, fps, config: CALM});
  const phone = spring({frame: frame - 3, fps, config: SOFT});
  const leave = interpolate(frame, [SCENE_LEN - 16, SCENE_LEN], [0, 1], {extrapolateLeft: 'clamp', extrapolateRight: 'clamp'});
  return (
    <AbsoluteFill style={{opacity}}>
      <div style={{position: 'absolute', top: 150, left: 90, right: 90, transform: `translateY(${-leave * 24}px)`}}>
        <div
          style={{
            fontFamily: '"Space Grotesk", sans-serif',
            fontWeight: 600,
            fontSize: 84,
            letterSpacing: -2.5,
            lineHeight: 1.05,
            opacity: title,
            transform: `translateY(${(1 - title) * 36}px)`,
          }}
        >
          {scene.title}
        </div>
        <div style={{marginTop: 22, fontSize: 40, lineHeight: 1.3, color: MUTED, opacity: line, transform: `translateY(${(1 - line) * 28}px)`}}>
          {scene.line}
        </div>
      </div>
      <div
        style={{
          position: 'absolute',
          left: (1080 - PHONE_W) / 2,
          top: 470,
          transform: `translateY(${(1 - phone) * 160 - leave * 30}px) scale(${0.96 + phone * 0.04})`,
          transformOrigin: '50% 0%',
        }}
      >
        <Phone screens={scene.screens} />
      </div>
      <div style={{position: 'absolute', left: 0, right: 0, bottom: 74}}>
        <Dots active={index} />
      </div>
    </AbsoluteFill>
  );
};

const Outro: React.FC = () => {
  const frame = useCurrentFrame();
  const {fps} = useVideoConfig();
  const rise = spring({frame, fps, config: SOFT});
  const sub = spring({frame: frame - 8, fps, config: CALM});
  const opacity = interpolate(frame, [0, 14], [0, 1], {extrapolateRight: 'clamp'});
  return (
    <AbsoluteFill style={{alignItems: 'center', justifyContent: 'center', opacity}}>
      <div style={{transform: `translateY(${(1 - rise) * 50}px)`, display: 'flex', flexDirection: 'column', alignItems: 'center'}}>
        <Wordmark size={150} />
        <div style={{marginTop: 30, fontSize: 42, color: MUTED, opacity: sub, textAlign: 'center', lineHeight: 1.35}}>
          Local-first. Your log stays on your phone.
          <br />
          Open source, MIT.
        </div>
      </div>
    </AbsoluteFill>
  );
};
