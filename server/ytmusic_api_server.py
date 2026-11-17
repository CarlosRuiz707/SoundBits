"""Servidor Tunebox <-> YouTube Music (ytmusicapi).

* Búsqueda, artistas y álbumes funcionan SIN iniciar sesión.
* Ver/crear playlists y añadir canciones requiere OAuth de YouTube Music.

Variables de entorno
  YTM_CLIENT_ID, YTM_CLIENT_SECRET  Credenciales OAuth (tipo "TVs and Limited Input devices")
  YTM_OAUTH_FILE                    Ruta del oauth.json (por defecto ./oauth.json)
  YTM_OAUTH_JSON                    Contenido de oauth.json (para hostings sin disco persistente)
  TUNEBOX_API_KEY                   Clave que la app envía en X-Tunebox-Key para las acciones que
                                    modifican tu cuenta. Si no se define, esas acciones se bloquean.
"""
import argparse
import re
import time
from urllib.parse import parse_qs, urlparse
import hmac
import json
import os
import sys
import tempfile
import threading

from flask import Flask, jsonify, request
from ytmusicapi import OAuthCredentials, YTMusic

app = Flask(__name__)

HERE = os.path.dirname(os.path.abspath(__file__))
OAUTH_FILE = os.environ.get('YTM_OAUTH_FILE') or os.path.join(HERE, 'oauth.json')

_lock = threading.Lock()
_pub = None
_auth = None


class AuthError(Exception):
    pass


def _oauth_path():
    raw = os.environ.get('YTM_OAUTH_JSON')
    if raw:
        path = os.path.join(tempfile.gettempdir(), 'tunebox_oauth.json')
        if not os.path.exists(path):
            with open(path, 'w', encoding='utf-8') as f:
                f.write(raw)
        return path
    return OAUTH_FILE if os.path.exists(OAUTH_FILE) else None


def get_pub():
    """Cliente sin sesión: suficiente para buscar."""
    global _pub
    with _lock:
        if _pub is None:
            _pub = YTMusic(language=os.environ.get('YTM_LANGUAGE', 'es'))
        return _pub


def get_yt():
    """Cliente con sesión OAuth: necesario para playlists."""
    global _auth
    with _lock:
        if _auth is not None:
            return _auth
        cid, secret = os.environ.get('YTM_CLIENT_ID'), os.environ.get('YTM_CLIENT_SECRET')
        if not cid or not secret:
            raise AuthError('Faltan YTM_CLIENT_ID y YTM_CLIENT_SECRET (credenciales OAuth de Google).')
        path = _oauth_path()
        if not path:
            raise AuthError('No existe oauth.json. Créalo con: ytmusicapi oauth --client-id ... --client-secret ...')
        _auth = YTMusic(path, oauth_credentials=OAuthCredentials(client_id=cid, client_secret=secret),
                        language=os.environ.get('YTM_LANGUAGE', 'es'))
        return _auth


def need_write():
    """Protege las acciones que modifican tu cuenta con una clave compartida."""
    key = os.environ.get('TUNEBOX_API_KEY')
    if not key:
        return jsonify({'error': 'Define TUNEBOX_API_KEY en el servidor para permitir esta acción.'}), 403
    sent = request.headers.get('X-Tunebox-Key', '') or request.args.get('key', '')
    if not hmac.compare_digest(sent.encode(), key.encode()):
        return jsonify({'error': 'Clave del servidor incorrecta.'}), 401
    return None


@app.before_request
def _private_guard():
    """Con TUNEBOX_API_KEY definida, nadie sin la clave usa el servidor (salvo /health)."""
    if request.method == 'OPTIONS' or request.path == '/health':
        return None
    key = os.environ.get('TUNEBOX_API_KEY')
    if not key:
        return None
    sent = request.headers.get('X-Tunebox-Key', '') or request.args.get('key', '')
    if not hmac.compare_digest(sent.encode(), key.encode()):
        return jsonify({'error': 'Acceso privado: clave incorrecta o ausente.'}), 401
    return None


@app.after_request
def cors(resp):
    resp.headers['Access-Control-Allow-Origin'] = '*'
    resp.headers['Access-Control-Allow-Headers'] = 'Content-Type, X-Tunebox-Key'
    resp.headers['Access-Control-Allow-Methods'] = 'GET, POST, OPTIONS'
    return resp


def thumb_url(item):
    thumbs = item.get('thumbnails') or []
    if thumbs and isinstance(thumbs[-1], dict):
        return thumbs[-1].get('url') or ''
    return ''


def first_artist(item, default='YouTube'):
    artists = item.get('artists') or []
    return (artists[0].get('name') if artists and isinstance(artists[0], dict) else None) or default


def normalize_song(item):
    album = item.get('album') or {}
    vid = item.get('videoId')
    return {
        'id': vid,
        'title': item.get('title') or 'Sin título',
        'artist': first_artist(item),
        'album': album.get('name') or 'YouTube',
        'art': thumb_url(item),
        'type': 'song',
        'provider': 'youtube',
        'source': 'youtube',
        'url': f'https://music.youtube.com/watch?v={vid}',
        'duration': item.get('duration_seconds') or 0,
    }


def normalize_album(item):
    return {
        'id': item.get('browseId'),
        'title': item.get('title') or 'Álbum',
        'artist': first_artist(item),
        'album': item.get('title') or 'Álbum',
        'art': thumb_url(item),
        'type': 'album',
        'provider': 'youtube',
        'source': 'youtube',
        'url': f"https://music.youtube.com/browse/{item.get('browseId')}",
    }


def normalize_artist(item):
    name = item.get('artist') or item.get('name') or 'Artista'
    return {
        'id': item.get('browseId'),
        'title': name,
        'artist': name,
        'album': 'YouTube',
        'art': thumb_url(item),
        'type': 'artist',
        'provider': 'youtube',
        'source': 'youtube',
    }


def create_playlist_from_query(title, description, query, limit=1):
    yt = get_yt()
    found = yt.search(query, filter='songs', limit=limit)
    if not found:
        raise ValueError(f'No se encontraron resultados para: {query}')
    video_id = found[0].get('videoId')
    if not video_id:
        raise ValueError(f'No se pudo obtener videoId para: {query}')
    playlist_id = yt.create_playlist(title, description)
    yt.add_playlist_items(playlist_id, [video_id])
    return {'playlistId': playlist_id, 'title': title, 'description': description,
            'videoId': video_id, 'query': query, 'added': [video_id]}


def auth_error(exc):
    return jsonify({'error': str(exc)}), 503


@app.get('/health')
def health():
    out = {'ok': True, 'provider': 'youtube', 'search': True, 'oauth': False,
           'write_protected': bool(os.environ.get('TUNEBOX_API_KEY'))}
    try:
        get_yt()
        out['oauth'] = True
    except Exception as exc:
        out['oauth_error'] = str(exc)
    return jsonify(out)


@app.get('/search')
def search():
    query = request.args.get('q', '').strip()
    if not query:
        return jsonify({'error': 'Falta el parámetro q'}), 400
    kind = request.args.get('type', 'all')
    try:
        limit = max(1, min(int(request.args.get('limit', 10)), 30))
    except ValueError:
        limit = 10
    yt = get_pub()
    results = []
    plan = [('songs', limit, normalize_song)]
    if kind != 'songs':
        plan += [('albums', 5, normalize_album), ('artists', 5, normalize_artist)]
    for flt, lim, norm in plan:
        try:
            results.extend(norm(i) for i in yt.search(query, filter=flt, limit=lim))
        except Exception:
            pass
    if kind == 'songs':
        results = [r for r in results if r.get('id')]
    return jsonify(results[:20 if kind != 'songs' else limit])


@app.get('/playlists')
def playlists():
    try:
        yt = get_yt()
    except AuthError as exc:
        return auth_error(exc)
    try:
        items = yt.get_library_playlists(limit=50)
        return jsonify([{'id': p.get('playlistId'), 'title': p.get('title') or 'Playlist',
                         'count': p.get('count')} for p in items if p.get('playlistId')])
    except Exception as exc:
        return jsonify({'error': f'Error leyendo playlists: {exc}'}), 500


@app.post('/playlist/create')
def create_playlist():
    denied = need_write()
    if denied:
        return denied
    try:
        yt = get_yt()
    except AuthError as exc:
        return auth_error(exc)
    payload = request.get_json(silent=True) or {}
    title = (payload.get('title') or 'Tunebox Playlist').strip() or 'Tunebox Playlist'
    description = (payload.get('description') or 'Creada desde Tunebox').strip()
    try:
        playlist_id = yt.create_playlist(title, description)
        return jsonify({'playlistId': playlist_id, 'title': title, 'description': description})
    except Exception as exc:
        return jsonify({'error': f'Error creando playlist: {exc}'}), 500


@app.post('/playlist/add')
def add_playlist_items():
    denied = need_write()
    if denied:
        return denied
    try:
        yt = get_yt()
    except AuthError as exc:
        return auth_error(exc)
    payload = request.get_json(silent=True) or {}
    playlist_id = payload.get('playlistId') or payload.get('playlist_id')
    video_ids = payload.get('videoIds') or payload.get('video_ids') or []
    video_id = payload.get('videoId') or payload.get('video_id')
    query = (payload.get('query') or payload.get('q') or '').strip()
    if not playlist_id:
        return jsonify({'error': 'Falta playlistId'}), 400
    try:
        ids = list(video_ids) or ([video_id] if video_id else [])
        if not ids and query:
            found = yt.search(query, filter='songs', limit=1)
            if not found or not found[0].get('videoId'):
                return jsonify({'error': f'No se encontraron resultados para: {query}'}), 404
            ids = [found[0]['videoId']]
        if not ids:
            return jsonify({'error': 'Falta videoId o query'}), 400
        res = yt.add_playlist_items(playlist_id, ids, duplicates=True)
        return jsonify({'playlistId': playlist_id, 'added': ids, 'status': res.get('status') if isinstance(res, dict) else None})
    except Exception as exc:
        return jsonify({'error': f'Error agregando a la playlist: {exc}'}), 500


@app.post('/playlist/create-and-add')
def create_and_add_playlist():
    denied = need_write()
    if denied:
        return denied
    payload = request.get_json(silent=True) or {}
    title = (payload.get('title') or 'Tunebox Playlist').strip() or 'Tunebox Playlist'
    description = (payload.get('description') or 'Creada desde Tunebox').strip()
    query = (payload.get('query') or payload.get('q') or '').strip()
    if not query:
        return jsonify({'error': 'Falta el parámetro query'}), 400
    try:
        return jsonify(create_playlist_from_query(title, description, query))
    except AuthError as exc:
        return auth_error(exc)
    except Exception as exc:
        return jsonify({'error': str(exc)}), 500


@app.get('/artist/<path:query>')
def artist(query):
    try:
        yt = get_pub()
        matches = yt.search(query, filter='artists')
        if not matches:
            return jsonify({'error': 'No se encontró el artista'}), 404
        artist_id = matches[0].get('browseId')
        data = yt.get_artist(artist_id)
        songs = [normalize_song(t) for t in (data.get('songs') or {}).get('results', [])[:10]] \
            if isinstance(data.get('songs'), dict) else [normalize_song(t) for t in (data.get('songs') or [])[:10]]
        albums_raw = data.get('albums') or {}
        albums_list = albums_raw.get('results', []) if isinstance(albums_raw, dict) else albums_raw
        return jsonify({
            'id': artist_id,
            'title': data.get('name') or matches[0].get('artist') or query,
            'artist': data.get('name') or query,
            'album': 'YouTube',
            'art': thumb_url(data),
            'type': 'artist', 'provider': 'youtube', 'source': 'youtube',
            'songs': songs,
            'albums': [normalize_album(a) for a in albums_list[:10]],
        })
    except Exception as exc:
        return jsonify({'error': f'Error buscando artista: {exc}'}), 500


@app.get('/album/<path:query>')
def album(query):
    try:
        yt = get_pub()
        matches = yt.search(query, filter='albums')
        if not matches:
            return jsonify({'error': 'No se encontró el álbum'}), 404
        album_id = matches[0].get('browseId')
        data = yt.get_album(album_id)
        return jsonify({
            'id': album_id,
            'title': data.get('title') or matches[0].get('title') or query,
            'artist': first_artist(data),
            'album': data.get('title') or query,
            'art': thumb_url(data),
            'type': 'album', 'provider': 'youtube', 'source': 'youtube',
            'tracks': [normalize_song(t) for t in data.get('tracks', [])],
        })
    except Exception as exc:
        return jsonify({'error': f'Error buscando álbum: {exc}'}), 500


def names(lst):
    """Lista de {name,id} -> 'A, B'."""
    if isinstance(lst, str):
        return lst
    out = [x.get('name') for x in (lst or []) if isinstance(x, dict) and x.get('name')]
    return ', '.join(out)


def normalize_any(c):
    """Convierte cualquier elemento del inicio de YouTube Music en un objeto sencillo para la app."""
    if not isinstance(c, dict):
        return None
    art = thumb_url(c)
    title = c.get('title') or ''
    if c.get('videoId'):
        return {'type': 'video' if c.get('views') else 'song', 'id': c['videoId'], 'title': title or 'Sin título',
                'artist': names(c.get('artists')) or c.get('views') or 'YouTube', 'art': art}
    if c.get('playlistId'):
        sub = c.get('description') or names(c.get('author')) or 'Playlist'
        return {'type': 'playlist', 'id': c['playlistId'], 'title': title or 'Playlist', 'artist': sub, 'art': art}
    bid = c.get('browseId')
    if bid:
        if str(bid).startswith('UC'):
            return {'type': 'artist', 'id': bid, 'title': title or 'Artista', 'artist': c.get('subscribers') and
                    f"{c['subscribers']} suscriptores" or 'Artista', 'art': art}
        return {'type': 'album', 'id': bid, 'title': title or 'Álbum',
                'artist': c.get('year') or names(c.get('artists')) or 'Álbum', 'art': art}
    return None


@app.get('/home')
def home():
    """Inicio de YouTube Music (sin iniciar sesión): filas con canciones, álbumes, playlists y artistas."""
    try:
        try:
            rows = max(1, min(int(request.args.get('rows', 6)), 12))
        except ValueError:
            rows = 6
        raw = get_pub().get_home(limit=rows)
        out = []
        for sec in raw:
            items = [i for i in (normalize_any(c) for c in (sec.get('contents') or [])) if i]
            if not items:
                continue
            out.append({
                'title': sec.get('title') or '',
                'strapline': sec.get('strapline') or '',
                'thumb': thumb_url(sec),
                'browseId': sec.get('browseId') or '',
                'items': items,
            })
        return jsonify(out)
    except Exception as exc:
        return jsonify({'error': f'Error cargando el inicio: {exc}'}), 500


@app.get('/browse/playlist/<pid>')
def browse_playlist(pid):
    try:
        data = get_pub().get_playlist(pid, limit=100)
        tracks = [normalize_song(t) for t in (data.get('tracks') or []) if t.get('videoId')]
        return jsonify({'title': data.get('title') or 'Playlist', 'subtitle': names(data.get('author')) or
                        data.get('description') or '', 'art': thumb_url(data), 'tracks': tracks, 'albums': []})
    except Exception as exc:
        return jsonify({'error': f'Error abriendo la playlist: {exc}'}), 500


@app.get('/browse/album/<bid>')
def browse_album(bid):
    try:
        data = get_pub().get_album(bid)
        art = thumb_url(data)
        tracks = []
        for t in (data.get('tracks') or []):
            if not t.get('videoId'):
                continue
            n = normalize_song(t)
            n['art'] = n['art'] or art
            n['album'] = data.get('title') or n['album']
            tracks.append(n)
        return jsonify({'title': data.get('title') or 'Álbum', 'subtitle': names(data.get('artists')) or '',
                        'art': art, 'tracks': tracks, 'albums': []})
    except Exception as exc:
        return jsonify({'error': f'Error abriendo el álbum: {exc}'}), 500


@app.get('/browse/artist/<cid>')
def browse_artist(cid):
    try:
        data = get_pub().get_artist(cid)
        songs = data.get('songs') or {}
        songs = songs.get('results', []) if isinstance(songs, dict) else songs
        albums = data.get('albums') or {}
        albums = albums.get('results', []) if isinstance(albums, dict) else albums
        return jsonify({'title': data.get('name') or 'Artista', 'subtitle': 'Artista', 'art': thumb_url(data),
                        'tracks': [normalize_song(t) for t in songs if t.get('videoId')],
                        'albums': [i for i in (normalize_any(a) for a in albums[:12]) if i]})
    except Exception as exc:
        return jsonify({'error': f'Error abriendo el artista: {exc}'}), 500


_stream_cache = {}


@app.get('/stream/<vid>')
def stream(vid):
    """URL directa del audio (yt-dlp). Solo uso personal: requiere TUNEBOX_API_KEY si está definida."""
    key = os.environ.get('TUNEBOX_API_KEY')
    if key:
        sent = request.headers.get('X-Tunebox-Key', '') or request.args.get('key', '')
        if not hmac.compare_digest(sent.encode(), key.encode()):
            return jsonify({'error': 'Clave del servidor incorrecta.'}), 401
    if not re.fullmatch(r'[A-Za-z0-9_-]{11}', vid or ''):
        return jsonify({'error': 'videoId no válido'}), 400
    now = time.time()
    hit = _stream_cache.get(vid)
    if hit and hit['exp'] > now + 120:
        return jsonify(hit['data'])
    try:
        import yt_dlp
    except ImportError:
        return jsonify({'error': 'Falta yt-dlp en el servidor (pip install yt-dlp).'}), 503
    try:
        opts = {'format': 'bestaudio[ext=m4a]/bestaudio/best', 'quiet': True,
                'no_warnings': True, 'noplaylist': True, 'skip_download': True}
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(f'https://music.youtube.com/watch?v={vid}', download=False)
        url = info.get('url')
        if not url:
            return jsonify({'error': 'No se obtuvo la URL de audio'}), 502
        try:
            exp = int(parse_qs(urlparse(url).query).get('expire', ['0'])[0])
        except ValueError:
            exp = 0
        exp = exp or int(now + 3600)
        data = {'url': url, 'dur': info.get('duration') or 0, 'ext': info.get('ext') or ''}
        _stream_cache[vid] = {'exp': exp, 'data': data}
        return jsonify(data)
    except Exception as exc:
        return jsonify({'error': f'Error obteniendo el audio: {exc}'}), 502


def run_cli():
    parser = argparse.ArgumentParser(description='Busca una canción y la añade a una playlist nueva de YouTube Music.')
    parser.add_argument('--query', '-q', help='Texto a buscar, por ejemplo: "Oasis Wonderwall"')
    parser.add_argument('--title', default='Tunebox Playlist', help='Nombre de la playlist')
    parser.add_argument('--description', default='Creada desde Tunebox', help='Descripción')
    parser.add_argument('--limit', type=int, default=1, help='Resultados a considerar (1 por defecto)')
    args = parser.parse_args()
    if not args.query:
        parser.print_help()
        return 1
    try:
        print(json.dumps(create_playlist_from_query(args.title, args.description, args.query, args.limit),
                         ensure_ascii=False, indent=2))
        return 0
    except Exception as exc:
        print(f'Error: {exc}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    if any(a in sys.argv[1:] for a in ('--query', '-q')):
        raise SystemExit(run_cli())
    app.run(host='0.0.0.0', port=int(os.environ.get('PORT', 3000)), debug=False)
