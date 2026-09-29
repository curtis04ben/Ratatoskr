from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles

from app.config import EXTRA_CORS_ORIGINS
from app.database import init_db
from app.routers import auth, generator, users, vault

app = FastAPI(title="Ratatoskr", version="2.3.0")

init_db()

# The web UI is same-origin (served below) and needs no CORS entry. Extra
# origins are for future clients that run from their own origin, notably a
# browser extension (chrome-extension://... / moz-extension://...).
if EXTRA_CORS_ORIGINS:
    app.add_middleware(
        CORSMiddleware,
        allow_origins=EXTRA_CORS_ORIGINS,
        allow_credentials=False,
        allow_methods=["*"],
        allow_headers=["*"],
    )

app.include_router(auth.router)
app.include_router(users.router)
app.include_router(vault.router)
app.include_router(generator.router)


@app.middleware("http")
async def security_headers(request, call_next):
    response = await call_next(request)
    response.headers["X-Content-Type-Options"] = "nosniff"
    response.headers["X-Frame-Options"] = "DENY"
    response.headers["Referrer-Policy"] = "no-referrer"
    response.headers["Cache-Control"] = "no-store"
    return response


app.mount("/", StaticFiles(directory="static", html=True), name="static")
