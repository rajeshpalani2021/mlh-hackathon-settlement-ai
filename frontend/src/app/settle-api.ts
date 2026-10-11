import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/** Backend base URL (Spring Boot app). */
export const API = 'http://localhost:8081/api';

export interface Trade {
  tradeRef: string;
  side: 'BUY' | 'SELL';
  securityName: string;
  isin: string;
  quantity: number;
  price: number;
  currency: string;
  tradeDate: string;
  settleDate: string;
  status: string;
  /** When the trade was loaded into Settle (ISO timestamp). */
  createdAt: string;
}

export interface Fix {
  field: string;
  qualifier: string;
  oldValue: string;
  newValue: string;
}

export interface Step {
  n: number;
  tool: string;
  args: unknown;
  summary: string;
  ts: string;
}

export interface Investigation {
  id: string;
  tradeRef: string;
  status: string;
  rootCause: string;
  explanation: string;
  confidence: number;
  fix: Fix | null;
  wikiPagesUsed: string[] | null;
  recipeUsed: string | null;
  steps: Step[] | null;
}

export interface ApproveResult {
  id: string;
  status: string;
  newStatus: string;
  recipeWritten: string | null;
}

export interface SwiftMessage {
  msgId: string;
  eventTs: string;
  direction: 'IN' | 'OUT';
  msgType: string;
  rawText: string;
  statusCode: string | null;
  reasonCode: string | null;
}

export interface TradeEvent {
  eventTs: string;
  eventType: string;
  reasonCode: string | null;
  counterpartyBic: string | null;
  minutesPending: number | null;
  detail: Record<string, unknown> | null;
}

export interface TradeHistory {
  tradeRef: string;
  messages: SwiftMessage[];
  events: TradeEvent[];
}

/** Upload/reset result: new trades sent, refs already in the DB (skipped), trades corrected by a learned recipe. */
export interface LoadResult {
  loaded: number;
  skipped: number;
  autoFixed: number;
}

export interface Metrics {
  investigations: number;
  verified: number;
  learnedFixesUsed: number;
  recipes: number;
  autoFixed: number;
}

export interface WikiDraft {
  id: number;
  code: string;
  investigation_id: string;
  status: string;
  nimble_sources: string[] | null;
  draft_page_path: string | null;
  created_at: string;
}

@Injectable({ providedIn: 'root' })
export class SettleApi {
  private http = inject(HttpClient);

  trades(): Observable<Trade[]> {
    return this.http.get<Trade[]>(`${API}/trades`);
  }

  history(tradeRef: string): Observable<TradeHistory> {
    return this.http.get<TradeHistory>(`${API}/trades/${tradeRef}/messages`);
  }

  metrics(): Observable<Metrics> {
    return this.http.get<Metrics>(`${API}/metrics`);
  }

  resetDemo(): Observable<LoadResult> {
    return this.http.post<LoadResult>(`${API}/demo/reset`, {});
  }

  upload(file: File): Observable<LoadResult> {
    const form = new FormData();
    form.append('file', file);
    return this.http.post<LoadResult>(`${API}/trades/upload`, form);
  }

  investigate(tradeRef: string): Observable<Investigation> {
    return this.http.post<Investigation>(`${API}/investigations`, { tradeRef });
  }

  approve(id: string): Observable<ApproveResult> {
    return this.http.post<ApproveResult>(`${API}/investigations/${id}/approve`, {});
  }

  drafts(): Observable<WikiDraft[]> {
    return this.http.get<WikiDraft[]>(`${API}/wiki/drafts`);
  }

  reviewDraft(id: number): Observable<{ id: number; status: string }> {
    return this.http.post<{ id: number; status: string }>(`${API}/wiki/drafts/${id}/review`, {});
  }
}
