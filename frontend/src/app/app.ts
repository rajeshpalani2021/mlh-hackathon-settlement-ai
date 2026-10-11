import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { DecimalPipe, PercentPipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Investigation, Metrics, SettleApi, Trade } from './settle-api';

type Filter = 'ALL' | 'GOOD' | 'FAIL';

const GOOD = ['NEW', 'SENT', 'ACK', 'MATCHED', 'SETTLED'];
const COLORS: Record<string, string> = {
  SETTLED: '#1bb8a5', SENT: '#0fb3a1', ACK: '#0fb3a1', MATCHED: '#2663f5',
  NAK: '#f26262', UNMATCHED: '#f0a500', PENDING: '#7c5cf0',
};

@Component({
  selector: 'app-root',
  imports: [DecimalPipe, PercentPipe],
  templateUrl: './app.html',
})
export class App implements OnInit, OnDestroy {
  private api = inject(SettleApi);
  private timer?: ReturnType<typeof setInterval>;

  trades = signal<Trade[]>([]);
  metrics = signal<Metrics | null>(null);
  filter = signal<Filter>('ALL');
  error = signal<string | null>(null);
  toastMsg = signal<string | null>(null);

  // AI panel
  panelRef = signal<string | null>(null);
  result = signal<Investigation | null>(null);
  busy = signal(false);

  good = computed(() => this.trades().filter(t => !this.isFail(t)).length);
  shown = computed(() => this.trades().filter(t =>
    this.filter() === 'ALL' || (this.filter() === 'GOOD') === !this.isFail(t)));

  ngOnInit(): void {
    this.load();
    // live refresh, paused while an investigation is open
    this.timer = setInterval(() => { if (!this.panelRef()) this.load(); }, 5000);
  }

  ngOnDestroy(): void {
    clearInterval(this.timer);
  }

  load(): void {
    this.api.trades().subscribe({
      next: t => { this.trades.set(t); this.error.set(null); },
      error: e => this.error.set(this.message(e)),
    });
    this.api.metrics().subscribe({ next: m => this.metrics.set(m), error: () => {} });
  }

  isFail(t: Trade): boolean {
    return !GOOD.includes(t.status);
  }

  color(status: string): string {
    return COLORS[status] ?? '#8a97ab';
  }

  resetDemo(): void {
    this.closePanel();
    this.api.resetDemo().subscribe({
      next: r => { this.toast(`Demo reset · ${r.loaded} trades sent`); this.load(); },
      error: e => this.toast(this.message(e)),
    });
  }

  upload(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file) return;
    this.api.upload(file).subscribe({
      next: r => { this.toast(`${r.loaded} trades uploaded and sent`); this.load(); },
      error: e => this.toast(this.message(e)),
    });
  }

  investigate(ref: string): void {
    this.panelRef.set(ref);
    this.result.set(null);
    this.busy.set(true);
    this.api.investigate(ref).subscribe({
      next: r => { this.result.set(r); this.busy.set(false); },
      error: e => {
        this.busy.set(false);
        this.result.set({
          id: '', tradeRef: ref, status: 'ERROR', rootCause: 'Investigation failed', explanation: this.message(e),
          confidence: 0, fix: null, wikiPagesUsed: null, recipeUsed: null, steps: null,
        });
      },
    });
  }

  approve(): void {
    const r = this.result();
    if (!r?.id) return;
    this.busy.set(true);
    this.api.approve(r.id).subscribe({
      next: a => {
        this.busy.set(false);
        const learned = a.recipeWritten ? ` · recipe saved: ${a.recipeWritten}` : '';
        this.toast(`${r.tradeRef} resent → ${a.status} (${a.newStatus})${learned}`);
        this.closePanel();
        this.load();
      },
      error: e => { this.busy.set(false); this.toast(this.message(e)); },
    });
  }

  closePanel(): void {
    this.panelRef.set(null);
    this.result.set(null);
  }

  private toast(msg: string): void {
    this.toastMsg.set(msg);
    setTimeout(() => this.toastMsg.set(null), 3500);
  }

  private message(e: unknown): string {
    if (e instanceof HttpErrorResponse) {
      if (e.status === 0) return 'Backend not reachable on localhost:8081';
      return e.error?.error ?? e.message;
    }
    return String(e);
  }
}
