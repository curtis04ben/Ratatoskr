from fastapi import APIRouter, Depends

from app import crypto
from app.deps import require_session
from app.schemas import GenerateRequest, GenerateResponse
from app.sessions import Session

router = APIRouter(prefix="/api/v1/generate", tags=["generate"])


@router.post("", response_model=GenerateResponse)
def generate(body: GenerateRequest, _session: Session = Depends(require_session)):
    # Gated behind any logged-in session (visitors included -- generating a
    # password touches no stored secrets) so the endpoint can't be hit
    # anonymously from the open network.
    password = crypto.generate_password(
        length=body.length,
        use_upper=body.use_upper,
        use_lower=body.use_lower,
        use_digits=body.use_digits,
        use_symbols=body.use_symbols,
        avoid_ambiguous=body.avoid_ambiguous,
    )
    return GenerateResponse(password=password)
