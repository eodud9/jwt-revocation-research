import http from 'k6/http';
import { check } from 'k6';


export const options = {
    vus: 1,
    iterations: 1,
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

    if (response.status !== 200) {
        console.log(`status=${response.status}, body=${response.body}`);
    }

    check(response, {

            'status is 200': (r) => r.status === 200,

        }
    );


}