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

export interface Metrics {
  investigations: number;
  verified: number;
  learnedFixesUsed: number;
  recipes: number;
}

@Injectable({ providedIn: 'root' })
export class SettleApi {
  private http = inject(HttpClient);

  trades(): Observable<Trade[]> {
    return this.http.get<Trade[]>(`${API}/trades`);
  }

  metrics(): Observable<Metrics> {
    return this.http.get<Metrics>(`${API}/metrics`);
  }

  resetDemo(): Observable<{ loaded: number }> {
    return this.http.post<{ loaded: number }>(`${API}/demo/reset`, {});
  }

  upload(file: File): Observable<{ loaded: number }> {
    const form = new FormData();
    form.append('file', file);
    return this.http.post<{ loaded: number }>(`${API}/trades/upload`, form);
  }

  investigate(tradeRef: string): Observable<Investigation> {
    return this.http.post<Investigation>(`${API}/investigations`, { tradeRef });
  }

  approve(id: string): Observable<ApproveResult> {
    return this.http.post<ApproveResult>(`${API}/investigations/${id}/approve`, {});
  }
}
