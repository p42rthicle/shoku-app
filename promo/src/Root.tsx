import {Composition} from 'remotion';
import {Showcase, FPS, TOTAL_FRAMES} from './Showcase';

export const Root: React.FC = () => (
  <Composition
    id="Showcase"
    component={Showcase}
    durationInFrames={TOTAL_FRAMES}
    fps={FPS}
    width={1080}
    height={1920}
  />
);
