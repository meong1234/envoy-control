function envoy_on_request(handle)
    local streamInfo = handle:streamInfo()
    local trusted_header = handle:metadata():get("x_client_name_trusted")
    if handle:headers():get("x-client-name-trusted") ~= nil then
        handle:headers():remove("x-client-name-trusted")
    end

    if handle:connection():ssl() and streamInfo:downstreamSslConnection() then
        local uriSanPeerCertificate = handle:streamInfo():downstreamSslConnection():uriSanPeerCertificate()
        if uriSanPeerCertificate ~= nil and next(uriSanPeerCertificate) ~= nil then
            local pattern = "://([a-zA-Z0-9-_.]+)"
            for _, entry in pairs(uriSanPeerCertificate) do
                handle:headers():add(trusted_header, string.match(entry, pattern))
            end
        end
    end
end

function envoy_on_response(handle)
end
