from fastapi import FastAPI
app = FastAPI(title="CellProbe API")
@app.get("/health")
async def health():
    return {"status": "ok"}
