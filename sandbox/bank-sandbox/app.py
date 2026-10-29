import hashlib
import hmac
import json
import os
from contextlib import asynccontextmanager
import urllib.request
import uuid
import sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[2] / 'shared'))
from python_common.validation import JsonBoundary

import pymysql
from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel, Field, ConfigDict

@asynccontextmanager
async def lifespan(_application):
    startup()
    yield


app = FastAPI(title='Local Bank Sandbox', lifespan=lifespan)
app.add_middleware(JsonBoundary)


def connect():
    return pymysql.connect(host=os.getenv('MYSQL_HOST', '127.0.0.1'), port=int(os.getenv('MYSQL_PORT','3306')),
                           user=os.getenv('MYSQL_USER','root'), password=os.environ['MYSQL_PASSWORD'],
                           database='fintech_bank', cursorclass=pymysql.cursors.DictCursor)


def auth(value):
    if not value or not hmac.compare_digest(value, os.environ['INTERNAL_TOKEN']):
        raise HTTPException(401, 'Internal authentication required')


class Payment(BaseModel):
    model_config = ConfigDict(strict=True, extra='forbid')
    payment_id: str = Field(pattern=r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')
    amount: int = Field(gt=0, le=9_000_000_000_000_000)


class Settlement(BaseModel):
    model_config = ConfigDict(strict=True, extra='forbid')
    payment_id: str = Field(pattern=r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')


@app.post('/payments')
def payment(request: Payment, x_internal_token: str = Header(default='')):
    auth(x_internal_token)
    with connect() as connection, connection.cursor() as cursor:
        cursor.execute('INSERT INTO bank_payments(payment_id,amount,provider_reference) VALUES(%s,%s,%s) ON DUPLICATE KEY UPDATE payment_id=payment_id',
                       (request.payment_id,request.amount,str(uuid.uuid4())))
        cursor.execute('SELECT * FROM bank_payments WHERE payment_id=%s',(request.payment_id,))
        row=cursor.fetchone()
        if row['amount'] != request.amount:
            raise HTTPException(409,'Payment amount mismatch')
        connection.commit()
        return row


@app.post('/settle')
def settle(request: Settlement, x_internal_token: str = Header(default='')):
    auth(x_internal_token)
    with connect() as connection, connection.cursor() as cursor:
        cursor.execute('SELECT * FROM bank_payments WHERE payment_id=%s',(request.payment_id,))
        row=cursor.fetchone()
        if not row:
            raise HTTPException(404,'Bank payment not found')
        cursor.execute("UPDATE bank_payments SET status='PAID' WHERE payment_id=%s",(request.payment_id,))
        connection.commit()
    payload=dict(payment_id=row['payment_id'],provider_reference=row['provider_reference'],amount=row['amount'],status='PAID')
    body=json.dumps(payload,separators=(',',':')).encode()
    canonical='POST\n/internal/webhook\n0\nbank\n'+hashlib.sha256(body).hexdigest()
    signature=hmac.new(os.environ['BANK_SECRET'].encode(),canonical.encode(),hashlib.sha256).hexdigest()
    callback=urllib.request.Request(os.getenv('PAYMENT_URL','http://127.0.0.1:8083')+'/internal/webhook',data=body,
                                   headers={'Content-Type':'application/json','X-Bank-Signature':signature})
    try:
        with urllib.request.urlopen(callback,timeout=15) as response:
            return json.loads(response.read())
    except Exception:
        raise HTTPException(503,'Bank settled; callback pending. Retry settlement with the same payment id.')


@app.get('/health')
def health():
    return dict(status='UP',mode='SANDBOX_NO_REAL_MONEY')


def startup():
    with connect() as connection, connection.cursor() as cursor:
        cursor.execute("CREATE TABLE IF NOT EXISTS bank_payments(payment_id VARCHAR(36) PRIMARY KEY,amount BIGINT NOT NULL,provider_reference VARCHAR(36) NOT NULL UNIQUE,status VARCHAR(20) NOT NULL DEFAULT 'PENDING') ENGINE=InnoDB")
        connection.commit()
