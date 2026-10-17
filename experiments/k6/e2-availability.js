import http from "k6/http"
import { check } from "k6"

export const options = {

    vus: 1,
    iterations: 100,

};

export default function (){

    const validToken = __ENV.VALID_TOKEN;
    const revokedToken = __ENV.REVOKED_TOKEN;

    const validResponse = http.get(

        'http://localhost:8080/api/protected',
        {
            headers: {
                        Authorization: `Bearer ${validToken}`,
                    },
        }

    );

    const revokedResponse = http.get(

            'http://localhost:8080/api/protected'
            headers: {
                Authorization: `Bearer ${revokedToken}`,
            },
        );

    check(validResponse, {
        'valid token allowed': (r) => r.status === 200,
    });

    check(revokedResponse, {
        'revoked token allowed': (r) => r.status === 200,
    });

}

