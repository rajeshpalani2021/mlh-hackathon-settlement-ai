import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { DecimalPipe, PercentPipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Investigation, Metrics, SettleApi, Trade, TradeEvent, TradeHistory } from './settle-api';

type Filter = 'ALL' | 'GOOD' | 'FAIL';

const GOOD = ['NEW', 'SENT', 'ACK', 'MATCHED', 'SETTLED'];
const COLORS: Record<string, string> = {
  SETTLED: '#1bb8a5', SENT: '#0fb3a1', ACK: '#0fb3a1', MATCHED: '#2663f5',
  NAK: '#f26262', UNMATCHED: '#f0a500', PENDING: '#7c5cf0', FIXED: '#1742b8',
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

  // trade detail (lifecycle + SWIFT messages)
  selectedRef = signal<string | null>(null);
  history = signal<TradeHistory | null>(null);
  openMsg = signal<string | null>(null);

  // loading screen, one-by-one reveal, status-change highlight
  loading = signal(true);
  private revealCount = signal<number | null>(null); // null = show all
  private revealTimer?: ReturnType<typeof setInterval>;
  flashed = signal<ReadonlySet<string>>(new Set());
  private lastStatus = new Map<string, string>();

  visible = computed(() => {
    const n = this.revealCount();
    return n === null ? this.trades() : this.trades().slice(0, n);
  });
  good = computed(() => this.visible().filter(t => !this.isFail(t)).length);
  shown = computed(() => this.visible().filter(t =>
    this.filter() === 'ALL' || (this.filter() === 'GOOD') === !this.isFail(t)));

  ngOnInit(): void {
    const started = Date.now();
    this.load(true, () => {
      // keep the loading screen up briefly so it doesn't just flicker
      setTimeout(() => this.loading.set(false), Math.max(0, 900 - (Date.now() - started)));
    });
    // live refresh, paused while an investigation is open
    this.timer = setInterval(() => { if (!this.panelRef()) this.load(); }, 5000);
  }

  ngOnDestroy(): void {
    clearInterval(this.timer);
    clearInterval(this.revealTimer);
  }

  /** reveal = show the trades one by one; done runs after the first response (ok or error). */
  load(reveal = false, done?: () => void): void {
    this.api.trades().subscribe({
      next: t => {
        this.trackStatusChanges(t, reveal);
        this.trades.set(t);
        this.error.set(null);
        if (reveal) this.reveal(t.length);
        done?.();
      },
      error: e => { this.error.set(this.message(e)); done?.(); },
    });
    this.api.metrics().subscribe({ next: m => this.metrics.set(m), error: () => {} });
    this.loadHistory();
  }

  private reveal(total: number): void {
    clearInterval(this.revealTimer);
    this.revealCount.set(0);
    this.revealTimer = setInterval(() => {
      const next = (this.revealCount() ?? total) + 1;
      if (next >= total) {
        clearInterval(this.revealTimer);
        this.revealCount.set(null);
      } else {
        this.revealCount.set(next);
      }
    }, 350);
  }

  /** Flash rows whose status changed since the last poll (e.g. ACK -> MATCHED on the next demo minute). */
  private trackStatusChanges(trades: Trade[], reset: boolean): void {
    if (reset) this.lastStatus.clear();
    const changed = new Set<string>();
    for (const t of trades) {
      const before = this.lastStatus.get(t.tradeRef);
      if (before !== undefined && before !== t.status) changed.add(t.tradeRef);
      this.lastStatus.set(t.tradeRef, t.status);
    }
    if (!changed.size) return;
    this.flashed.set(new Set([...this.flashed(), ...changed]));
    setTimeout(() => {
      const left = new Set(this.flashed());
      changed.forEach(r => left.delete(r));
      this.flashed.set(left);
    }, 2500);
  }

  select(ref: string): void {
    if (this.selectedRef() === ref) {
      this.selectedRef.set(null);
      this.history.set(null);
      return;
    }
    this.selectedRef.set(ref);
    this.history.set(null);
    this.openMsg.set(null);
    this.loadHistory();
  }

  private loadHistory(): void {
    const ref = this.selectedRef();
    if (!ref) return;
    this.api.history(ref).subscribe({
      next: h => { if (this.selectedRef() === ref) this.history.set(h); },
      error: () => { this.selectedRef.set(null); this.history.set(null); },
    });
  }

  toggleMsg(id: string): void {
    this.openMsg.set(this.openMsg() === id ? null : id);
  }

  time(ts: string): string {
    return new Date(ts).toLocaleTimeString();
  }

  eventNote(e: TradeEvent): string {
    const d = e.detail ?? {};
    if (e.eventType === 'FIXED') return `:${d['field']}::${d['qualifier']} ${d['oldValue']} → ${d['newValue']}`;
    const parts: string[] = [];
    if (e.reasonCode) parts.push(`reason ${e.reasonCode}`);
    if (e.minutesPending != null) parts.push(`pending ${e.minutesPending} demo min`);
    if (d['msgRef']) parts.push(`ref ${d['msgRef']}`);
    return parts.join(' · ');
  }

  isFail(t: Trade): boolean {
    return !GOOD.includes(t.status);
  }

  color(status: string): string {
    return COLORS[status] ?? '#8a97ab';
  }

  resetDemo(): void {
    this.closePanel();
    this.selectedRef.set(null);
    this.history.set(null);
    this.api.resetDemo().subscribe({
      next: r => { this.toast(`Demo reset · ${r.loaded} trades sent`); this.load(true); },
      error: e => this.toast(this.message(e)),
    });
  }

  upload(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file) return;
    this.api.upload(file).subscribe({
      next: r => { this.toast(`${r.loaded} trades uploaded and sent`); this.load(true); },
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
