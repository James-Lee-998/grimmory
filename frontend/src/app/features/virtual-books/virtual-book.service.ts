import {inject, Injectable} from '@angular/core';
import {HttpClient, HttpParams} from '@angular/common/http';
import {Observable} from 'rxjs';
import {API_CONFIG} from '../../core/config/api-config';

export interface VirtualBookMirror {
  id: number;
  provider: string;
  format: string;
  qualityScore: number;
}

export interface VirtualBook {
  id: number;
  /** Canonical ID derived from title, author and language; shared by all providers of the same book. */
  virtualBookId: number;
  title: string;
  authors: string | null;
  language: string | null;
  issuedDate: string | null;
  summary: string | null;
  downloaded: boolean;
  mirrors: VirtualBookMirror[];
}

export interface VirtualBookPage {
  content: VirtualBook[];
  page: {
    size: number;
    number: number;
    totalElements: number;
    totalPages: number;
  };
}

export interface VirtualBookDownloadResponse {
  taskId: string;
  status: string;
}

@Injectable({
  providedIn: 'root'
})
export class VirtualBookService {

  private readonly url = `${API_CONFIG.BASE_URL}/api/v1/virtual-books`;
  private http = inject(HttpClient);

  list(search: string, page: number, size: number): Observable<VirtualBookPage> {
    let params = new HttpParams().set('page', page).set('size', size);
    if (search.trim()) {
      params = params.set('search', search.trim());
    }
    return this.http.get<VirtualBookPage>(this.url, {params});
  }

  download(id: number, mirrorId: number, libraryPathId: number): Observable<VirtualBookDownloadResponse> {
    return this.http.post<VirtualBookDownloadResponse>(`${this.url}/${id}/download`, {mirrorId, libraryPathId});
  }
}
