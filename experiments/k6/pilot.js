import http from 'k6/http';
import { check } from 'k6';


export const options = {
    vus: Number(__ENV.VUS || 10),
    duration: '10s',
};

export default function (){

    const token = __ENV.ACCESS_TOKEN;
    const response = http.get(
    'http://localhost:8080/api/protected',
        {
            headers: {
                Authorization: `Bearer ${token}`,
            },
        }
    )

    check(response, {

            'status is 200': (r) => r.status === 200,

        }
    );


}