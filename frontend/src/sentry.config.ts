import * as Sentry from '@sentry/angular';

export function initSentry() {
  Sentry.init({
    dsn: 'https://f2ffc578da129aedf2b3823439064233@o4512238797062144.ingest.us.sentry.io/4512238867120128',
    environment: 'hackathon',
    release: 'settle@0.1.0',
    tracesSampleRate: 1.0,
    integrations: [
      Sentry.browserTracingIntegration(),
      Sentry.replayIntegration({
        maskAllText: true,
        blockAllMedia: true,
      }),
    ],
  });
}
