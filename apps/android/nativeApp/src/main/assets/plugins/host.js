let host;
export function setHost(value) {
  host = value;
}
export function getYaqmcClient() {
  return { invoke: (...args) => host(...args) };
}
