import http from "k6/http"
import { check } from "k6"

export const options = {
    vus: 1,
    iterations: 100,
}

export default function(){

    const revokedToken = __ENV.REVOKED_TOKEN;

    const response = http.get(
        'http://localhost:8080/api/protected',
        {
            headers: {
                Authorization: `Bearer ${revokedToken}`,
            }
        }
    )

    check(
        response,
        {
            'revoked token allowed': (r) => r.status === 200,
        }
    );

}