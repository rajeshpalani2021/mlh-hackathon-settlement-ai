import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { DatePipe, DecimalPipe, PercentPipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Investigation, Metrics, SettleApi, Trade, TradeEvent, TradeHistory, WikiDraft } from './settle-api';

type Filter = 'ALL' | 'GOOD' | 'FAIL';

const GOOD = ['NEW', 'SENT', 'ACK', 'MATCHED', 'SETTLED'];
const COLORS: Record<string, string> = {
  SETTLED: '#1bb8a5', SENT: '#0fb3a1', ACK: '#0fb3a1', MATCHED: '#2663f5',
  NAK: '#f26262', UNMATCHED: '#f0a500', PENDING: '#7c5cf0', FIXED: '#1742b8', AUTO_FIXED: '#0b7d70',
};

@Component({
  selector: 'app-root',
  imports: [DatePipe, DecimalPipe, PercentPipe],
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

  // wiki drafts (Nimble research)
  drafts = signal<WikiDraft[]>([]);

  // loading screen, one-by-one reveal of new trades, status-change highlight
  loading = signal(true);
  private hidden = signal<ReadonlySet<string>>(new Set()); // trades waiting to be revealed
  private revealTimer?: ReturnType<typeof setInterval>;
  flashed = signal<ReadonlySet<string>>(new Set());
  private lastStatus = new Map<string, string>();

  // grid sort on Created (newest first by default; the API already returns newest first)
  createdSort = signal<'desc' | 'asc'>('desc');

  visible = computed(() => this.trades().filter(t => !this.hidden().has(t.tradeRef)));
  good = computed(() => this.visible().filter(t => !this.isFail(t)).length);
  shown = computed(() => {
    const dir = this.createdSort() === 'desc' ? -1 : 1;
    return this.visible()
      .filter(t => this.filter() === 'ALL' || (this.filter() === 'GOOD') === !this.isFail(t))
      .sort((a, b) => dir * a.createdAt.localeCompare(b.createdAt) || a.tradeRef.localeCompare(b.tradeRef));
  });

  toggleCreatedSort(): void {
    this.createdSort.set(this.createdSort() === 'desc' ? 'asc' : 'desc');
  }

  // pagination (20 trades per page)
  readonly pageSize = 20;
  page = signal(1);
  pageCount = computed(() => Math.max(1, Math.ceil(this.shown().length / this.pageSize)));
  paged = computed(() => {
    const p = Math.min(this.page(), this.pageCount());
    return this.shown().slice((p - 1) * this.pageSize, p * this.pageSize);
  });
  pageFrom = computed(() => this.shown().length ? (Math.min(this.page(), this.pageCount()) - 1) * this.pageSize + 1 : 0);
  pageTo = computed(() => Math.min(this.shown().length, this.pageFrom() + this.pageSize - 1));

  ngOnInit(): void {
    // show every trade already in the DB (with its current status) behind the loading screen
    const t0 = Date.now();
    const done = () => setTimeout(() => this.loading.set(false), Math.max(0, 900 - (Date.now() - t0)));
    this.load(true, done);
    // live refresh, paused while an investigation is open
    this.timer = setInterval(() => { if (!this.panelRef()) this.load(); }, 3000);
  }

  ngOnDestroy(): void {
    clearInterval(this.timer);
    clearInterval(this.revealTimer);
  }

  setFilter(f: Filter): void {
    this.filter.set(f);
    this.page.set(1);
  }

  goPage(p: number): void {
    this.page.set(Math.min(Math.max(1, p), this.pageCount()));
  }

  /**
   * reveal = show trades that were not on screen before one by one (first load: the first page;
   * after an upload: the new trades). done runs after the first response (ok or error).
   */
  load(reveal = false, done?: () => void): void {
    const known = new Set(this.trades().map(t => t.tradeRef));
    const firstLoad = known.size === 0;
    this.api.trades().subscribe({
      next: t => {
        this.trackStatusChanges(t);
        this.trades.set(t);
        this.error.set(null);
        if (reveal) {
          const fresh = t.filter(x => !known.has(x.tradeRef)).map(x => x.tradeRef);
          this.reveal(firstLoad ? fresh.slice(0, this.pageSize) : fresh);
        }
        done?.();
      },
      error: e => { this.error.set(this.message(e)); done?.(); },
    });
    this.api.metrics().subscribe({ next: m => this.metrics.set(m), error: () => {} });
    this.api.drafts().subscribe({ next: d => this.drafts.set(d), error: () => {} });
    this.loadHistory();
  }

  private reveal(refs: string[]): void {
    clearInterval(this.revealTimer);
    if (!refs.length) {
      this.hidden.set(new Set());
      return;
    }
    const queue = [...refs];
    this.hidden.set(new Set(queue));
    this.revealTimer = setInterval(() => {
      queue.shift();
      this.hidden.set(new Set(queue));
      if (!queue.length) clearInterval(this.revealTimer);
    }, 350);
  }

  /** Flash rows whose status changed since the last poll (e.g. ACK -> MATCHED on the next demo minute). */
  private trackStatusChanges(trades: Trade[]): void {
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
    if (e.eventType === 'AUTO_FIXED') {
      return `learned fix ${d['recipe']}: :${d['field']}::${d['qualifier']} ${d['oldValue']} → ${d['newValue']}`;
    }
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
      next: r => {
        this.trades.set([]);
        this.page.set(1);
        this.toast(`Demo reset · ${r.loaded} trades sent`);
        this.load(true);
      },
      error: e => this.toast(this.message(e)),
    });
  }

  upload(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file) return;
    this.api.upload(file).subscribe({
      next: r => {
        const parts = [`${r.loaded} new trades sent`];
        if (r.skipped) parts.push(`${r.skipped} already loaded`);
        if (r.autoFixed) parts.push(`${r.autoFixed} auto-fixed by learned recipe`);
        this.toast(parts.join(' · '));
        this.page.set(1); // newest trades are listed first
        this.load(true);
      },
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

  reviewDraft(id: number): void {
    this.api.reviewDraft(id).subscribe({
      next: () => {
        this.toast('Draft marked as reviewed');
        this.load();
      },
      error: e => this.toast(this.message(e)),
    });
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
