import hmac
import json
import os
import threading
from contextlib import asynccontextmanager
import sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[2] / 'shared'))
from python_common.validation import JsonBoundary

import pymysql
from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel, Field, ConfigDict
from kafka import KafkaConsumer
import re

@asynccontextmanager
async def lifespan(_application):
    startup()
    stopped = threading.Event()
    worker = threading.Thread(target=consume, args=(stopped,), daemon=True)
    worker.start()
    try:
        yield
    finally:
        stopped.set()
        worker.join()


app = FastAPI(title='FinTech Risk Rules', lifespan=lifespan)
app.add_middleware(JsonBoundary)


def connect():
    return pymysql.connect(host=os.getenv('MYSQL_HOST', '127.0.0.1'), port=int(os.getenv('MYSQL_PORT', '3306')),
                           user=os.getenv('MYSQL_USER', 'root'), password=os.environ['MYSQL_PASSWORD'],
                           database='fintech_risk', cursorclass=pymysql.cursors.DictCursor)


def require(token):
    if not token or not hmac.compare_digest(token, os.environ['INTERNAL_TOKEN']):
        raise HTTPException(401, 'Internal authentication required')


class Check(BaseModel):
    model_config = ConfigDict(strict=True, extra='forbid')
    transaction_id: str = Field(pattern=r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')
    user_id: str = Field(pattern=r'^(alice|bob|admin)$')
    amount: int = Field(gt=0, le=9_000_000_000_000_000)
    ip: str = Field(min_length=1, max_length=80, pattern=r'^[A-Za-z0-9_.:-]+$')


@app.post('/check')
def check(request: Check, x_internal_token: str = Header(default='')):
    require(x_internal_token)
    with connect() as connection, connection.cursor() as cursor:
        cursor.execute('INSERT INTO risk_users(user_id,last_ip) VALUES(%s,%s) ON DUPLICATE KEY UPDATE user_id=user_id', (request.user_id, request.ip))
        cursor.execute('SELECT last_ip FROM risk_users WHERE user_id=%s FOR UPDATE', (request.user_id,))
        last_ip = cursor.fetchone()['last_ip']
        cursor.execute('SELECT * FROM risk_checks WHERE transaction_id=%s', (request.transaction_id,))
        existing = cursor.fetchone()
        if existing:
            if (existing['user_id'], existing['amount'], existing['ip']) != (request.user_id, request.amount, request.ip):
                raise HTTPException(409, 'Risk operation payload mismatch')
            return dict(allowed=bool(existing['allowed']), reason=existing['reason'])
        cursor.execute('SELECT COUNT(*) AS count FROM risk_checks WHERE user_id=%s AND created_at>DATE_SUB(NOW(),INTERVAL 1 MINUTE)', (request.user_id,))
        count = cursor.fetchone()['count']
        reason = 'AMOUNT_LIMIT' if request.amount > 250_000_000 else 'VELOCITY_LIMIT' if count >= 10 else 'NEW_IP_LARGE_AMOUNT' if request.ip != last_ip and request.amount > 5_000_000 else 'OK'
        allowed = reason == 'OK'
        cursor.execute('INSERT INTO risk_checks(transaction_id,user_id,amount,ip,allowed,reason) VALUES(%s,%s,%s,%s,%s,%s)',
                       (request.transaction_id, request.user_id, request.amount, request.ip, allowed, reason))
        if allowed:
            cursor.execute('UPDATE risk_users SET last_ip=%s WHERE user_id=%s', (request.ip, request.user_id))
        connection.commit()
        return dict(allowed=allowed, reason=reason)


@app.get('/flags')
def flags(x_internal_token: str = Header(default='')):
    require(x_internal_token)
    with connect() as connection, connection.cursor() as cursor:
        cursor.execute('SELECT transaction_id,user_id,amount,ip,reason,created_at FROM risk_checks WHERE allowed=FALSE ORDER BY created_at DESC LIMIT 100')
        return cursor.fetchall()


@app.get('/health')
def health():
    with connect() as connection, connection.cursor() as cursor:
        cursor.execute('SELECT 1')
    return dict(status='UP')


def consume(stopped):
    while not stopped.is_set():
        consumer = None
        try:
            consumer = KafkaConsumer('financial-transactions', bootstrap_servers=os.getenv('KAFKA_BOOTSTRAP', '127.0.0.1:9092'),
                                     group_id='fintech-risk', auto_offset_reset='earliest', enable_auto_commit=False,
                                     value_deserializer=lambda body: json.loads(body), consumer_timeout_ms=1000)
            while not stopped.is_set():
                for message in consumer:
                    if stopped.is_set():
                        break
                    event = message.value
                    Check(transaction_id=event['event_id'],user_id=event['user_id'],amount=event['amount'],ip=event['ip'])
                    if event['event_id']!=event['transaction_id']:
                        raise ValueError('Event transaction mismatch')
                    if set(event)!={'event_id','transaction_id','user_id','source','destination','amount','kind','journal_id','ip','source_owner','destination_owner'}:
                        raise ValueError('Invalid event schema')
                    if event['kind'] not in {'TRANSFER','DEPOSIT','WITHDRAWAL','PAYMENT'} or event['source']==event['destination']:
                        raise ValueError('Invalid event transfer')
                    if not all(isinstance(event[key],str) and re.fullmatch(r'[A-Za-z0-9_-]{1,100}',event[key]) for key in ['source','destination']):
                        raise ValueError('Invalid account identifier')
                    with connect() as connection, connection.cursor() as cursor:
                        cursor.execute('SELECT payload FROM observed_events WHERE event_id=%s',(event['event_id'],))
                        existing=cursor.fetchone()
                        if existing and json.loads(existing['payload'])!=event:
                            raise ValueError('Event ID reused with different payload')
                        cursor.execute('INSERT INTO observed_events(event_id,payload) VALUES(%s,%s) ON DUPLICATE KEY UPDATE event_id=event_id', (event['event_id'], json.dumps(event)))
                        connection.commit()
                    consumer.commit()
        except Exception:
            stopped.wait(3)
        finally:
            if consumer is not None:
                consumer.close(autocommit=False)


def startup():
    with connect() as connection, connection.cursor() as cursor:
        cursor.execute('CREATE TABLE IF NOT EXISTS risk_users(user_id VARCHAR(80) PRIMARY KEY,last_ip VARCHAR(80) NOT NULL) ENGINE=InnoDB')
        cursor.execute('CREATE TABLE IF NOT EXISTS risk_checks(transaction_id VARCHAR(36) PRIMARY KEY,user_id VARCHAR(80),amount BIGINT,ip VARCHAR(80),allowed BOOLEAN,reason VARCHAR(80),created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,INDEX(user_id,created_at)) ENGINE=InnoDB')
        cursor.execute('CREATE TABLE IF NOT EXISTS observed_events(event_id VARCHAR(36) PRIMARY KEY,payload JSON,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP) ENGINE=InnoDB')
        connection.commit()
