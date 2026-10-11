import * as Sentry from '@sentry/angular';

export function initSentry() {
  Sentry.init({
    dsn: 'YOUR_FRONTEND_DSN',
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
