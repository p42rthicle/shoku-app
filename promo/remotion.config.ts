import {Config} from '@remotion/cli/config';

// The phone screens come straight from the screenshot tests' output; nothing is copied.
Config.setPublicDir('../docs/screenshots');
Config.setVideoImageFormat('jpeg');
Config.setJpegQuality(95);
Config.setCodec('h264');
Config.setPixelFormat('yuv420p');
Config.setCrf(20);
Config.setOverwriteOutput(true);
