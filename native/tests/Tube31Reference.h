#pragma once

#include <cstdint>
#include <array>

// Iscle: Verified with the latest version at 13/12/2022

class Tube31Reference {
public:
    Tube31Reference();

    void Reset();
    void SetEnable(bool enable);
    void TubeProcess(float *buffer, uint32_t size);

private:
    std::array<double, 2> acc;
    bool enable;
};
