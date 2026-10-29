import json
from starlette.responses import JSONResponse


def unique_object(pairs):
    result={}
    for key,value in pairs:
        if key in result:
            raise ValueError('Duplicate JSON field')
        result[key]=value
    return result


def check_nesting(body):
    depth=0
    quoted=False
    escaped=False
    for character in body:
        if quoted:
            if escaped:escaped=False
            elif character==92:escaped=True
            elif character==34:quoted=False
        elif character==34:quoted=True
        elif character in (91,123):
            depth+=1
            if depth>16:raise ValueError('JSON nesting exceeds 16 levels')
        elif character in (93,125):depth-=1


class JsonBoundary:
    def __init__(self,app):
        self.app=app

    async def __call__(self,scope,receive,send):
        if scope['type']!='http' or scope['method'] not in {'POST','PUT','PATCH'}:
            return await self.app(scope,receive,send)
        headers=dict(scope['headers'])
        if headers.get(b'content-type',b'').split(b';')[0].lower()!=b'application/json':
            return await JSONResponse({'code':'JSON_CONTENT_TYPE_REQUIRED'},status_code=415)(scope,receive,send)
        body=bytearray()
        while True:
            message=await receive()
            if message['type']=='http.disconnect':return
            body.extend(message.get('body',b''))
            if len(body)>65536:
                return await JSONResponse({'code':'PAYLOAD_TOO_LARGE'},status_code=413)(scope,receive,send)
            if not message.get('more_body'):break
        try:
            check_nesting(body)
            value=json.loads(bytes(body),object_pairs_hook=unique_object,parse_constant=lambda value: (_ for _ in ()).throw(ValueError('Invalid JSON number')))
            if not isinstance(value,dict):raise ValueError('JSON object required')
        except (ValueError,UnicodeDecodeError,RecursionError):
            return await JSONResponse({'code':'INVALID_JSON'},status_code=422)(scope,receive,send)
        delivered=False

        async def replay():
            nonlocal delivered
            if not delivered:
                delivered=True
                return {'type':'http.request','body':bytes(body),'more_body':False}
            return await receive()

        await self.app(scope,replay,send)
