import http from "k6/http";
import { check } from "k6";

export const options = {
    vus: Number(__ENV.VUS),
    duration: '30s',
}

export default function(){

    const token = __ENV.ACCESS_TOKEN;

    const response = http.get(
        'http://localhost:8080/api/protected',
        {
            headers: {
                Authorization: `Bearer ${token}`,
            }
        }
    );

    check(response,
    'status is 200': (r) => r.status === 200,
    );

}
