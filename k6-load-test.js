import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  stages: [
    { duration: '1m', target: 700 },  // Ramp up to 700 users
    { duration: '3m', target: 700 },  // Stay at 700 users
    { duration: '1m', target: 0 },    // Ramp down to 0
  ],
  thresholds: {
    http_req_duration: ['p(95)<500'],
    http_req_failed: ['rate<0.01'],
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export default function () {
  // Get all books
  let res = http.get(`${BASE_URL}/api/books`);
  check(res, { 'get all books status 200': (r) => r.status === 200 });

  sleep(1);

  // Get thread info
  res = http.get(`${BASE_URL}/api/books/thread-info`);
  check(res, { 'thread info status 200': (r) => r.status === 200 });

  sleep(1);

  // Get book by ID
  const bookId = Math.floor(Math.random() * 100) + 1;
  res = http.get(`${BASE_URL}/api/books/${bookId}`);
  check(res, { 'get book by id status': (r) => r.status === 200 || r.status === 404 });

  sleep(1);

  // Create book
  const payload = JSON.stringify({
    title: `Book ${Date.now()}`,
    author: `Author ${Math.random()}`,
  });
  res = http.post(`${BASE_URL}/api/books`, payload, {
    headers: { 'Content-Type': 'application/json' },
  });
  check(res, { 'create book status 200': (r) => r.status === 200 });

  sleep(2);
}
