import json
import unittest

from python_common.validation import JsonBoundary


class JsonBoundaryTest(unittest.IsolatedAsyncioTestCase):
    async def request(self, body):
        messages = []
        forwarded = []

        async def endpoint(scope, receive, send):
            forwarded.append((await receive())['body'])
            await send({'type': 'http.response.start', 'status': 200, 'headers': []})
            await send({'type': 'http.response.body', 'body': b'{}'})

        async def receive():
            return {'type': 'http.request', 'body': body, 'more_body': False}

        async def send(message):
            messages.append(message)

        await JsonBoundary(endpoint)(
            {'type': 'http', 'method': 'POST', 'headers': [(b'content-type', b'application/json')]},
            receive, send)
        return messages[0]['status'], messages[-1]['body'], forwarded

    async def test_rejects_nested_arrays_before_forwarding(self):
        for depth in (17, 1501):
            with self.subTest(depth=depth):
                for body in (
                    b'{"value":' + b'[' * (depth - 1) + b'0' + b']' * (depth - 1) + b'}',
                    b'{"value":' * depth + b'0' + b'}' * depth,
                ):
                    status, response, forwarded = await self.request(body)
                    self.assertEqual(422, status)
                    self.assertEqual('INVALID_JSON', json.loads(response)['code'])
                    self.assertEqual([], forwarded)

    async def test_allows_depth_sixteen_and_ignores_brackets_inside_strings(self):
        nested = b'{"value":' + b'[' * 15 + b'0' + b']' * 15 + b'}'
        escaped = json.dumps({'value': '"\\' + ('[{]}' * 1500)}).encode()
        for body in (nested, escaped):
            status, _, forwarded = await self.request(body)
            self.assertEqual(200, status)
            self.assertEqual([body], forwarded)

    async def test_rejects_duplicate_fields_invalid_numbers_and_non_objects(self):
        for body in (b'{"amount":1,"amount":2}', b'{"amount":NaN}', b'[]', b'{"value":'):
            status, response, forwarded = await self.request(body)
            self.assertEqual(422, status)
            self.assertEqual('INVALID_JSON', json.loads(response)['code'])
            self.assertEqual([], forwarded)


if __name__ == '__main__':
    unittest.main()
