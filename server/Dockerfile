FROM python:3.12-slim

# argon2-cffi needs a C toolchain to build its wheel on some architectures;
# gosu is how the entrypoint drops from root to the app's unprivileged user
# after fixing /data's ownership (see docker-entrypoint.sh) -- it exists
# specifically for this, and handles signal forwarding/PID 1 correctly in
# a way plain `su`/`sudo` don't. Neither stays in the final image layer
# beyond what apt itself needs.
RUN apt-get update \
    && apt-get install -y --no-install-recommends build-essential gosu \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /srv

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app ./app
COPY static ./static
COPY docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
RUN chmod +x /usr/local/bin/docker-entrypoint.sh

RUN useradd --create-home --shell /usr/sbin/nologin ratatoskr \
    && mkdir -p /data \
    && chown -R ratatoskr:ratatoskr /srv /data

VOLUME ["/data"]
ENV RATATOSKR_DATA_DIR=/data

EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=3s --start-period=10s \
    CMD python -c "import urllib.request as u; u.urlopen('http://127.0.0.1:8000/api/v1/auth/status', timeout=2)" || exit 1

# Deliberately no USER directive here: the container must start as root so
# the entrypoint can fix /data's ownership on a freshly bind-mounted host
# directory, then it drops privileges itself via gosu before the
# application process actually runs. See docker-entrypoint.sh.
ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
