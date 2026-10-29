import importlib.util
import threading
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch


def load_service(name, relative_path):
    source = Path(__file__).resolve().parents[2] / relative_path
    specification = importlib.util.spec_from_file_location(name, source)
    module = importlib.util.module_from_spec(specification)
    specification.loader.exec_module(module)
    return module


fraud = load_service('fraud_app', 'services/fraud-service/app.py')
bank = load_service('bank_app', 'sandbox/bank-sandbox/app.py')


class ServiceLifecycleTest(unittest.IsolatedAsyncioTestCase):
    async def test_bank_initializes_schema_once(self):
        connection = MagicMock()
        connection.__enter__.return_value = connection
        cursor = connection.cursor.return_value.__enter__.return_value
        with patch.object(bank, 'connect', return_value=connection) as connect:
            async with bank.app.router.lifespan_context(bank.app):
                connect.assert_called_once()
                self.assertEqual(1, cursor.execute.call_count)
                connection.commit.assert_called_once()
            connect.assert_called_once()

    async def test_fraud_starts_one_worker_and_closes_consumer_on_shutdown(self):
        for fail_poll in (False, True):
            with self.subTest(fail_poll=fail_poll):
                polling = threading.Event()
                closed = threading.Event()
                workers = []
                real_thread = threading.Thread
                connection = MagicMock()
                connection.__enter__.return_value = connection
                cursor = connection.cursor.return_value.__enter__.return_value

                class Consumer:
                    def __iter__(self):
                        return self

                    def __next__(self):
                        polling.set()
                        if fail_poll:
                            raise RuntimeError('Kafka unavailable')
                        closed.wait(0.01)
                        raise StopIteration

                    def close(self, autocommit):
                        if autocommit:
                            raise AssertionError('Shutdown must not commit unprocessed events')
                        closed.set()

                def create_worker(*args, **kwargs):
                    worker = real_thread(*args, **kwargs)
                    workers.append(worker)
                    return worker

                with patch.object(fraud, 'connect', return_value=connection) as connect, \
                        patch.object(fraud, 'KafkaConsumer', return_value=Consumer()) as consumer, \
                        patch.object(fraud.threading, 'Thread', side_effect=create_worker):
                    async with fraud.app.router.lifespan_context(fraud.app):
                        self.assertTrue(polling.wait(1), 'Consumer never started')
                        connect.assert_called_once()
                        self.assertEqual(3, cursor.execute.call_count)
                        connection.commit.assert_called_once()
                        consumer.assert_called_once()
                        self.assertEqual(1000, consumer.call_args.kwargs['consumer_timeout_ms'])
                        self.assertEqual(1, len(workers))
                        self.assertTrue(workers[0].is_alive())
                    self.assertFalse(workers[0].is_alive())
                    self.assertTrue(closed.is_set())
                    connect.assert_called_once()


if __name__ == '__main__':
    unittest.main()
